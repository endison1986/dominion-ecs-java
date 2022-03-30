/*
 * Copyright (c) 2021 Enrico Stara
 * This code is licensed under the MIT license. See the LICENSE file in the project root for license terms.
 */

package dev.dominion.ecs.engine;

import dev.dominion.ecs.api.Results;
import dev.dominion.ecs.engine.collections.ChunkedPool;
import dev.dominion.ecs.engine.collections.ChunkedPool.IdSchema;
import dev.dominion.ecs.engine.collections.ObjectArrayPool;
import dev.dominion.ecs.engine.system.ClassIndex;
import dev.dominion.ecs.engine.system.IndexKey;
import dev.dominion.ecs.engine.system.LoggingSystem;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

public final class Composition {
    public static final int COMPONENT_INDEX_CAPACITY = 1 << 10;
    private static final System.Logger LOGGER = LoggingSystem.getLogger();
    private final Class<?>[] componentTypes;
    private final CompositionRepository repository;
    private final ChunkedPool.Tenant<IntEntity> tenant;
    private final ObjectArrayPool arrayPool;
    private final ClassIndex classIndex;
    private final IdSchema idSchema;
    private final int[] componentIndex;
    private final Map<IndexKey, IntEntity> states = new ConcurrentHashMap<>();
    private final StampedLock stateLock = new StampedLock();
    private final LoggingSystem.Context loggingContext;

    public Composition(CompositionRepository repository, ChunkedPool.Tenant<IntEntity> tenant
            , ObjectArrayPool arrayPool, ClassIndex classIndex, IdSchema idSchema, LoggingSystem.Context loggingContext
            , Class<?>... componentTypes) {
        this.repository = repository;
        this.tenant = tenant;
        this.arrayPool = arrayPool;
        this.classIndex = classIndex;
        this.idSchema = idSchema;
        this.componentTypes = componentTypes;
        this.loggingContext = loggingContext;
        if (isMultiComponent()) {
            componentIndex = new int[COMPONENT_INDEX_CAPACITY];
            for (int i = 0; i < length(); i++) {
                componentIndex[classIndex.getIndex(componentTypes[i])] = i + 1;
            }
        } else {
            componentIndex = null;
        }
        if (LoggingSystem.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, LoggingSystem.format(loggingContext.subject()
                            , "Creating " + this)
            );
        }
    }

    public static <S extends Enum<S>> IndexKey calcIndexKey(S state, ClassIndex classIndex) {
        int cIndex = classIndex.getIndex(state.getClass());
        cIndex = cIndex == 0 ? classIndex.getIndexOrAddClass(state.getClass()) : cIndex;
        return new IndexKey(new int[]{cIndex, state.ordinal()});
    }

    public int length() {
        return componentTypes.length;
    }

    public boolean isMultiComponent() {
        return length() > 1;
    }

    public int fetchComponentIndex(Class<?> componentType) {
        return componentIndex[classIndex.getIndex(componentType)] - 1;
    }

    public Object[] sortComponentsInPlaceByIndex(Object[] components) {
        int newIdx;
        for (int i = 0; i < components.length; i++) {
            newIdx = fetchComponentIndex(components[i].getClass());
            if (newIdx != i) {
                swapComponents(components, i, newIdx);
            }
        }
        newIdx = fetchComponentIndex(components[0].getClass());
        if (newIdx > 0) {
            swapComponents(components, 0, newIdx);
        }
        return components;
    }

    private void swapComponents(Object[] components, int i, int newIdx) {
        Object temp = components[newIdx];
        components[newIdx] = components[i];
        components[i] = temp;
    }

    public IntEntity createEntity(Object... components) {
        int id = tenant.nextId();
        return tenant.register(id, new IntEntity(id, this,
                isMultiComponent() ? sortComponentsInPlaceByIndex(components) : components));
    }

    public boolean deleteEntity(IntEntity entity) {
        detachEntity(entity);
        Object[] components = entity.getComponents();
        if (components != null && entity.isPooledArray()) {
            arrayPool.push(components);
        }
        entity.setData(null);
        return true;
    }

    public IntEntity attachEntity(IntEntity entity, Object... components) {
        entity = tenant.register(entity.setId(tenant.nextId()), switch (length()) {
            case 0 -> entity.setData(new IntEntity.Data(this, null, entity.getData().stateRoot()));
            case 1 -> entity.setData(new IntEntity.Data(this, components, entity.getData().stateRoot()));
            default -> entity.setData(new IntEntity.Data(this, sortComponentsInPlaceByIndex(components), entity.getData().stateRoot()));
        });
        if (LoggingSystem.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, LoggingSystem.format(loggingContext.subject()
                            , "Attaching " + entity)
            );
        }
        return entity;
    }

    public void reattachEntity(IntEntity entity) {
        tenant.register(entity.setId(tenant.nextId()), entity);
    }

    public IntEntity detachEntity(IntEntity entity) {
        tenant.freeId(entity.getId());
        entity.flagDetachedId();
        if (LoggingSystem.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, LoggingSystem.format(loggingContext.subject()
                            , "Detaching " + entity)
            );
        }
        return entity;
    }

    public <S extends Enum<S>> IntEntity setEntityState(IntEntity entity, S state) {
        boolean detached = detachEntityState(entity);
        if (LoggingSystem.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, LoggingSystem.format(loggingContext.subject()
                            , "Detaching state from " + entity + " : " + detached)
            );
        }
        if (state != null) {
            attachEntityState(entity, state);
        }
        return entity;
    }

    private boolean detachEntityState(IntEntity entity) {
        IndexKey key = entity.getData().stateRoot();
        // if entity is root
        if (key != null) {
            // if alone
            if (entity.getPrev() == null) {
                if (states.remove(key) != null) {
                    entity.setData(new IntEntity.Data(this, entity.getComponents(), null));
                    return true;
                }
            } else {
                IntEntity prev = (IntEntity) entity.getPrev();
                if (states.replace(key, entity, prev)) {
                    prev.setNext(null);
                    prev.setData(new IntEntity.Data(this, prev.getComponents(), entity.getData().stateRoot()));
                    entity.setPrev(null);
                    entity.setData(new IntEntity.Data(this, entity.getComponents(), null));
                    return true;
                }
            }
        } else if (entity.getNext() != null) {
            long stamp = stateLock.writeLock();
            try {
                IntEntity prev, next;
                if ((next = (IntEntity) entity.getNext()) != null) {
                    if ((prev = (IntEntity) entity.getPrev()) != null) {
                        prev.setNext(next);
                        next.setPrev(prev);
                    } else {
                        next.setPrev(null);
                    }
                }
                entity.setPrev(null);
                entity.setNext(null);
                return true;
            } finally {
                stateLock.unlockWrite(stamp);
            }
        }
        return false;
    }

    private <S extends Enum<S>> void attachEntityState(IntEntity entity, S state) {
        IndexKey indexKey = calcIndexKey(state, classIndex);
        IntEntity prev = states.computeIfAbsent(indexKey
                , k -> entity.setData(new IntEntity.Data(this, entity.getComponents(), k)));
        if (prev != entity) {
            states.computeIfPresent(indexKey, (k, oldEntity) -> {
                entity.setPrev(oldEntity);
                entity.setData(new IntEntity.Data(this, entity.getComponents(), k));
                oldEntity.setNext(entity);
                oldEntity.setData(new IntEntity.Data(this, oldEntity.getComponents(), null));
                return entity;
            });
        }
        if (LoggingSystem.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, LoggingSystem.format(loggingContext.subject()
                            , "Attaching state "
                                    + state.getClass().getSimpleName() + "." + state
                                    + " to " + entity)
            );
        }
    }

    public Class<?>[] getComponentTypes() {
        return componentTypes;
    }

    public CompositionRepository getRepository() {
        return repository;
    }

    public ChunkedPool.Tenant<IntEntity> getTenant() {
        return tenant;
    }

    public Map<IndexKey, IntEntity> getStates() {
        return Collections.unmodifiableMap(states);
    }

    public IntEntity getStateRootEntity(IndexKey key) {
        return states.get(key);
    }

    public IdSchema getIdSchema() {
        return idSchema;
    }

    @Override
    public String toString() {
        int iMax = componentTypes.length - 1;
        if (iMax == -1)
            return "Composition=[]";
        StringBuilder b = new StringBuilder("Composition=[");
        for (int i = 0; ; i++) {
            b.append(componentTypes[i].getSimpleName());
            if (i == iMax)
                return b.append(']').toString();
            b.append(", ");
        }
    }

    public <T> Iterator<Results.Comp1<T>> select(Class<T> type, Iterator<IntEntity> iterator) {
        int idx = componentIndex == null ? 0 : fetchComponentIndex(type);
        return new Comp1Iterator<>(idx, iterator, this);
    }

    public <T1, T2> Iterator<Results.Comp2<T1, T2>> select(Class<T1> type1, Class<T2> type2, Iterator<IntEntity> iterator) {
        return new Comp2Iterator<>(
                fetchComponentIndex(type1),
                fetchComponentIndex(type2),
                iterator, this);
    }

    public <T1, T2, T3> Iterator<Results.Comp3<T1, T2, T3>> select(Class<T1> type1, Class<T2> type2, Class<T3> type3, Iterator<IntEntity> iterator) {
        return new Comp3Iterator<>(
                fetchComponentIndex(type1),
                fetchComponentIndex(type2),
                fetchComponentIndex(type3),
                iterator, this);
    }

    public <T1, T2, T3, T4> Iterator<Results.Comp4<T1, T2, T3, T4>> select(Class<T1> type1, Class<T2> type2, Class<T3> type3, Class<T4> type4, Iterator<IntEntity> iterator) {
        return new Comp4Iterator<>(
                fetchComponentIndex(type1),
                fetchComponentIndex(type2),
                fetchComponentIndex(type3),
                fetchComponentIndex(type4),
                iterator, this);
    }

    public <T1, T2, T3, T4, T5> Iterator<Results.Comp5<T1, T2, T3, T4, T5>> select(Class<T1> type1, Class<T2> type2, Class<T3> type3, Class<T4> type4, Class<T5> type5, Iterator<IntEntity> iterator) {
        return new Comp5Iterator<>(
                fetchComponentIndex(type1),
                fetchComponentIndex(type2),
                fetchComponentIndex(type3),
                fetchComponentIndex(type4),
                fetchComponentIndex(type5),
                iterator, this);
    }

    public <T1, T2, T3, T4, T5, T6> Iterator<Results.Comp6<T1, T2, T3, T4, T5, T6>> select(Class<T1> type1, Class<T2> type2, Class<T3> type3, Class<T4> type4, Class<T5> type5, Class<T6> type6, Iterator<IntEntity> iterator) {
        return new Comp6Iterator<>(
                fetchComponentIndex(type1),
                fetchComponentIndex(type2),
                fetchComponentIndex(type3),
                fetchComponentIndex(type4),
                fetchComponentIndex(type5),
                fetchComponentIndex(type6),
                iterator, this);
    }

    public static class StateIterator implements Iterator<IntEntity> {
        private IntEntity currentEntity;

        public StateIterator(IntEntity rootEntity) {
            currentEntity = new IntEntity(IdSchema.DETACHED_BIT, null);
            currentEntity.setPrev(rootEntity);
        }

        @Override
        public boolean hasNext() {
            return currentEntity.getPrev() != null;
        }

        @Override
        public IntEntity next() {
            return currentEntity = (IntEntity) currentEntity.getPrev();
        }
    }

    record Comp1Iterator<T>(int idx, Iterator<IntEntity> iterator,
                            Composition composition) implements Iterator<Results.Comp1<T>> {
        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @SuppressWarnings({"unchecked", "StatementWithEmptyBody"})
        @Override
        public Results.Comp1<T> next() {
            IntEntity intEntity;
            IntEntity.Data data;
            while ((data = (intEntity = iterator.next()).getData()).composition() != composition) {
            }
            Object[] components = data.components();
            return new Results.Comp1<>((T) components[idx], intEntity);
        }
    }

    record Comp2Iterator<T1, T2>(int idx1, int idx2,
                                 Iterator<IntEntity> iterator,
                                 Composition composition) implements Iterator<Results.Comp2<T1, T2>> {
        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @SuppressWarnings({"unchecked", "StatementWithEmptyBody"})
        @Override
        public Results.Comp2<T1, T2> next() {
            IntEntity intEntity;
            IntEntity.Data data;
            while ((data = (intEntity = iterator.next()).getData()).composition() != composition) {
            }
            Object[] components = data.components();
            return new Results.Comp2<>((T1) components[idx1], (T2) components[idx2], intEntity);
        }
    }

    record Comp3Iterator<T1, T2, T3>(int idx1, int idx2, int idx3,
                                     Iterator<IntEntity> iterator,
                                     Composition composition) implements Iterator<Results.Comp3<T1, T2, T3>> {
        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @SuppressWarnings({"unchecked", "StatementWithEmptyBody"})
        @Override
        public Results.Comp3<T1, T2, T3> next() {
            IntEntity intEntity;
            IntEntity.Data data;
            while ((data = (intEntity = iterator.next()).getData()).composition() != composition) {
            }
            Object[] components = data.components();
            return new Results.Comp3<>(
                    (T1) components[idx1],
                    (T2) components[idx2],
                    (T3) components[idx3],
                    intEntity);
        }
    }

    record Comp4Iterator<T1, T2, T3, T4>(int idx1, int idx2, int idx3, int idx4,
                                         Iterator<IntEntity> iterator,
                                         Composition composition) implements Iterator<Results.Comp4<T1, T2, T3, T4>> {
        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @SuppressWarnings({"unchecked", "StatementWithEmptyBody"})
        @Override
        public Results.Comp4<T1, T2, T3, T4> next() {
            IntEntity intEntity;
            IntEntity.Data data;
            while ((data = (intEntity = iterator.next()).getData()).composition() != composition) {
            }
            Object[] components = data.components();
            return new Results.Comp4<>(
                    (T1) components[idx1],
                    (T2) components[idx2],
                    (T3) components[idx3],
                    (T4) components[idx4],
                    intEntity);
        }
    }

    record Comp5Iterator<T1, T2, T3, T4, T5>(int idx1, int idx2, int idx3, int idx4, int idx5,
                                             Iterator<IntEntity> iterator,
                                             Composition composition) implements Iterator<Results.Comp5<T1, T2, T3, T4, T5>> {
        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @SuppressWarnings({"unchecked", "StatementWithEmptyBody"})
        @Override
        public Results.Comp5<T1, T2, T3, T4, T5> next() {
            IntEntity intEntity;
            IntEntity.Data data;
            while ((data = (intEntity = iterator.next()).getData()).composition() != composition) {
            }
            Object[] components = data.components();
            return new Results.Comp5<>(
                    (T1) components[idx1],
                    (T2) components[idx2],
                    (T3) components[idx3],
                    (T4) components[idx4],
                    (T5) components[idx5],
                    intEntity);
        }
    }

    record Comp6Iterator<T1, T2, T3, T4, T5, T6>(int idx1, int idx2, int idx3, int idx4, int idx5, int idx6,
                                                 Iterator<IntEntity> iterator,
                                                 Composition composition) implements Iterator<Results.Comp6<T1, T2, T3, T4, T5, T6>> {
        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @SuppressWarnings({"unchecked", "StatementWithEmptyBody"})
        @Override
        public Results.Comp6<T1, T2, T3, T4, T5, T6> next() {
            IntEntity intEntity;
            IntEntity.Data data;
            while ((data = (intEntity = iterator.next()).getData()).composition() != composition) {
            }
            Object[] components = data.components();
            return new Results.Comp6<>(
                    (T1) components[idx1],
                    (T2) components[idx2],
                    (T3) components[idx3],
                    (T4) components[idx4],
                    (T5) components[idx5],
                    (T6) components[idx6],
                    intEntity);
        }
    }
}
