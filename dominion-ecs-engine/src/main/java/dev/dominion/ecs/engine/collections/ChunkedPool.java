/*
 * Copyright (c) 2021 Enrico Stara
 * This code is licensed under the MIT license. See the LICENSE file in the project root for license terms.
 */

package dev.dominion.ecs.engine.collections;

import dev.dominion.ecs.engine.system.IDUpdater;
import dev.dominion.ecs.engine.system.Logging;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * The ChunkedPool class is the core of the Dominion project.
 * This custom data structure implements multi-tenant management of a pool of items organized into linked
 * chunks to improve performance and have dynamic capacity.
 *
 * @param <T> the managed type that must implement the {@link Item} interface
 */
public final class ChunkedPool<T extends ChunkedPool.Item> implements AutoCloseable {
    private static final System.Logger LOGGER = Logging.getLogger();
    private final LinkedChunk<T>[] chunks;
    private final List<Tenant<T>> tenants = new ArrayList<>();
    private final IdSchema idSchema;
    private final Logging.Context loggingContext;
    private int chunkIndex = -1;

    @SuppressWarnings("unchecked")
    public ChunkedPool(IdSchema idSchema, Logging.Context loggingContext) {
        this.idSchema = idSchema;
        this.loggingContext = loggingContext;
        if (Logging.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, Logging.format(loggingContext.subject()
                            , "Creating " + this
                    )
            );
        }
        chunks = new LinkedChunk[idSchema.chunkCount];
    }

    @Override
    public String toString() {
        return "ChunkedPool={"
                + "chunkCount=" + idSchema.chunkCount
                + ", chunkCapacity=" + idSchema.chunkCapacity
                + '}';
    }

    private LinkedChunk<T> newChunk(Tenant<T> owner, LinkedChunk<T> previousChunk, IDUpdater idUpdater) {
        int id = ++chunkIndex;
        LinkedChunk<T> newChunk = new LinkedChunk<>(id, idSchema, previousChunk, owner.dataLength, owner, idUpdater, loggingContext);
        if (previousChunk != null) {
            previousChunk.setNext(newChunk);
        }
        chunks[id] = newChunk;
        return newChunk;
    }

    public LinkedChunk<T> getChunk(int id) {
        return chunks[idSchema.fetchChunkId(id)];
    }

    public T getEntry(int id) {
        return getChunk(id).get(id);
    }

    public Tenant<T> newTenant() {
        return newTenant(0, null, null, IDUpdater.ID_UPDATER);
    }

    public Tenant<T> newTenant(int dataLength, Object owner, Object subject, IDUpdater idUpdater) {
        Tenant<T> newTenant = new Tenant<>(this, idSchema, dataLength, owner, subject, idUpdater, loggingContext);
        tenants.add(newTenant);
        return newTenant;
    }

    public Iterator<T> allEntities() {
        return new PoolAllEntitiesIterator<>(this.chunks, chunkIndex);
    }

    public int size() {
        int sum = 0;
        for (int i = 0; i <= chunkIndex; i++) {
            var chunk = chunks[i];
            sum += chunk.size();
        }
        return sum;
    }

    @Override
    public void close() {
        tenants.forEach(Tenant::close);
    }


    // INTERFACES

    public interface Item {
        int getId();

        void setId(int id);

        int getStateId();

        void setStateId(int id);

        LinkedChunk<? extends Item> getChunk();

        LinkedChunk<? extends Item> getStateChunk();
    }

    public interface PoolIteratorNextWith1 {
        Object fetchNext(Object[] dataArray, int next, Item item);

        Object fetchNext(Object[][] multiDataArray, int i1, int next, Item item);
    }

    public interface PoolIteratorNextWith2 {
        Object fetchNext(Object[][] multiDataArray, int i1, int i2, int next, Item item);
    }

    public interface PoolIteratorNextWith3 {
        Object fetchNext(Object[][] multiDataArray, int i1, int i2, int i3, int next, Item item);
    }

    public interface PoolIteratorNextWith4 {
        Object fetchNext(Object[][] multiDataArray, int i1, int i2, int i3, int i4, int next, Item item);
    }

    public interface PoolIteratorNextWith5 {
        Object fetchNext(Object[][] multiDataArray, int i1, int i2, int i3, int i4, int i5, int next, Item item);
    }

    public interface PoolIteratorNextWith6 {
        Object fetchNext(Object[][] multiDataArray, int i1, int i2, int i3, int i4, int i5, int i6, int next, Item item);
    }


    // ID-SCHEMA

    // |--FLAGS--|--CHUNK_ID--|--OBJECT_ID--|
    public record IdSchema(
            int chunkBit
            ,
            int chunkCount,
            int chunkIdBitMask,
            int chunkIdBitMaskShifted
            ,
            int chunkCapacity,
            int objectIdBitMask
    ) {
        public static final int TOTAL_BIT = 31;
        public static final int MIN_CHUNK_BIT = 8;
        public static final int MAX_CHUNK_BIT = 16;
        public static final int DETACHED_BIT_IDX = 31;
        public static final int DETACHED_BIT = 1 << DETACHED_BIT_IDX;

        public IdSchema(int chunkBit) {
            this(chunkBit
                    , 1 << (TOTAL_BIT - chunkBit)
                    , (1 << (TOTAL_BIT - chunkBit)) - 1
                    , ((1 << (TOTAL_BIT - chunkBit)) - 1) << chunkBit
                    , 1 << Math.min(chunkBit, MAX_CHUNK_BIT)
                    , (1 << chunkBit) - 1
            );
        }

        public String idToString(int id) {
            return "|" + ((id & DETACHED_BIT) >>> DETACHED_BIT_IDX)
                    + ":" + (fetchChunkId(id))
                    + ":" + (fetchObjectId(id))
                    + "|";
        }

        public int createId(int chunkId, int objectId) {
            return (chunkId & chunkIdBitMask) << chunkBit
                    | (objectId & objectIdBitMask);
        }

        public int fetchChunkId(int id) {
            return (id >>> chunkBit) & chunkIdBitMask;
        }

        public int fetchObjectId(int id) {
            return id & objectIdBitMask;
        }
    }

    // TENANT

    public static final class Tenant<T extends Item> implements AutoCloseable {
        private static final AtomicInteger idGenerator = new AtomicInteger();
        private final int id = idGenerator.getAndIncrement();
        private final ChunkedPool<T> pool;
        private final IdSchema idSchema;
        private final IntStack idStack;
        private final Logging.Context loggingContext;
        private final IDUpdater idUpdater;
        private final int dataLength;
        private final Object owner;
        private final Object subject;
        private final LinkedChunk<T> firstChunk;
        private LinkedChunk<T> currentChunk;
        private int nextId = IdSchema.DETACHED_BIT;

        private Tenant(
                ChunkedPool<T> pool,
                IdSchema idSchema,
                int dataLength,
                Object owner,
                Object subject,
                IDUpdater idUpdater,
                Logging.Context loggingContext
        ) {
            this.pool = pool;
            this.idSchema = idSchema;
            this.dataLength = dataLength;
            this.owner = owner;
            this.subject = subject;
            this.idUpdater = idUpdater;
            this.loggingContext = loggingContext;
            idStack = new IntStack(IdSchema.DETACHED_BIT, idSchema.chunkCapacity << 3);
            currentChunk = pool.newChunk(this, null, idUpdater);
            firstChunk = currentChunk;
            nextId();
            if (Logging.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
                LOGGER.log(
                        System.Logger.Level.DEBUG, Logging.format(loggingContext.subject()
                                , "Creating " + this
                        )
                );
            }
        }

        @Override
        public String toString() {
            return "Tenant={" +
                    "id=" + id +
                    ", dataLength=" + dataLength +
                    ", nextId=" + idSchema.idToString(nextId) +
                    ", subject=" + subject +
                    '}';
        }

        public int nextId() {
            boolean loggable = Logging.isLoggable(loggingContext.levelIndex(), System.Logger.Level.TRACE);
            if (loggable) {
                LOGGER.log(
                        System.Logger.Level.TRACE, Logging.format(loggingContext.subject()
                                , "Getting nextId from " + currentChunk
                                        + " having current nextId " + idSchema.idToString(nextId)
                        )
                );
            }
            int returnValue = idStack.pop();
            if (loggable) {
                LOGGER.log(
                        System.Logger.Level.TRACE, Logging.format(loggingContext.subject()
                                , "Popping nextId:" + idSchema.idToString(returnValue)
                        )
                );
            }
            if (returnValue != IdSchema.DETACHED_BIT) {
                pool.getChunk(returnValue).decrementRmCount();
                return returnValue;
            }
//            returnValue = nextId;
            synchronized (this) {
                returnValue = nextId;
                if (currentChunk.index < idSchema.chunkCapacity - 1) {
                    nextId = idSchema.createId(currentChunk.id, currentChunk.incrementIndex());
                    return returnValue;
                }
                currentChunk = pool.newChunk(this, currentChunk, currentChunk.idUpdater);
                nextId = idSchema.createId(currentChunk.id, currentChunk.incrementIndex());
                return returnValue;
//                for (; ; ) {
//                    var currentIndex = currentChunk.index;
//                    if (currentIndex < idSchema.chunkCapacity - 1) {
//                        if (currentChunk.compareAndSet(currentIndex, currentIndex + 1)) {
//                            nextId = idSchema.createId(currentChunk.id, currentIndex + 1);
//                            return returnValue;
//                        }
//                    } else {
//                        currentChunk = pool.newChunk(this, currentChunk, currentChunk.idUpdater);
//                        currentIndex = currentChunk.index;
//                        if (currentChunk.compareAndSet(currentIndex, currentIndex + 1)) {
//                            nextId = idSchema.createId(currentChunk.id, currentChunk.incrementIndex());
//                            return returnValue;
//                        }
//                    }
//                }
            }
        }

        public PoolDataIterator<T> iterator() {
            return dataLength == 1 ?
                    new PoolDataIterator<>(firstChunk, idSchema) :
                    new PoolMultiDataIterator<>(firstChunk, idSchema);
        }

        public PoolDataIterator<T> noItemIterator() {
            return dataLength == 1 ?
                    new PoolDataNoItemIterator<>(firstChunk, idSchema) :
                    new PoolMultiDataNoItemIterator<>(firstChunk, idSchema);
        }

        public PoolDataIterator<T> iteratorWithState(boolean multiData) {
            return multiData ?
                    new PoolMultiDataIteratorWithState<>(firstChunk, idSchema) :
                    new PoolDataIteratorWithState<>(firstChunk, idSchema);
        }

        public PoolDataIterator<T> noItemIteratorWithState(boolean multiData) {
            return multiData ?
                    new PoolMultiDataNoItemIteratorWithState<>(firstChunk, idSchema) :
                    new PoolDataNoItemIteratorWithState<>(firstChunk, idSchema);
        }

        public PoolSizeIterator<T> sizeIterator() {
            return new PoolSizeIterator<>(firstChunk, idSchema);
        }

        public T register(T entry, Object[] data) {
            final var id = nextId();
            idUpdater.setId(entry, id);
            return pool.getChunk(id).set(entry, data);
        }

        public void migrate(
                T entry,
                int newId,
                int[] indexMapping,
                int[] addedIndexMapping,
                Object addedComponent,
                Object[] addedComponents
        ) {
            LinkedChunk<T> prevChunk = pool.getChunk(entry.getId());
            LinkedChunk<T> newChunk = pool.getChunk(newId);
            newChunk.copy(entry, prevChunk, newId, indexMapping);
            if (addedIndexMapping != null) {
                newChunk.add(newId, addedIndexMapping, addedComponent, addedComponents);
            }
        }

        public int currentChunkSize() {
            return currentChunk.size();
        }

        public ChunkedPool<T> getPool() {
            return pool;
        }

        public Object getOwner() {
            return owner;
        }

        public Object getSubject() {
            return subject;
        }

        @Override
        public void close() {
            idStack.close();
        }
    }


    // LINKED-CHUNK

    public static final class LinkedChunk<T extends Item> {
        private static final System.Logger LOGGER = Logging.getLogger();
        private static final AtomicIntegerFieldUpdater<LinkedChunk> INDEX_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(LinkedChunk.class, "index");
        private final IdSchema idSchema;
        private final Item[] itemArray;
        private final Object[] dataArray;
        private final Object[][] multiDataArray;
        private final Tenant<T> tenant;
        private final int id;
        private final int dataLength;
        private final IDUpdater idUpdater;
        private final AtomicInteger rmCount = new AtomicInteger(0);
        private final LinkedChunk<T> previous;
        private LinkedChunk<T> next;
        private volatile int index = -1;
        private int sizeOffset = 0;

        public LinkedChunk(
                int id,
                IdSchema idSchema,
                LinkedChunk<T> previous,
                int dataLength,
                Tenant<T> tenant,
                IDUpdater idUpdater,
                Logging.Context loggingContext
        ) {
            this.idSchema = idSchema;
            this.dataLength = dataLength;
            itemArray = new Item[idSchema.chunkCapacity];
            dataArray = dataLength == 1 ? new Object[idSchema.chunkCapacity * dataLength] : null;
            multiDataArray = dataLength > 1 ? new Object[dataLength][idSchema.chunkCapacity * dataLength] : null;
            this.previous = previous;
            this.tenant = tenant;
            this.id = id;
            this.idUpdater = idUpdater;
            if (Logging.isLoggable(loggingContext.levelIndex(), System.Logger.Level.DEBUG)) {
                LOGGER.log(
                        System.Logger.Level.DEBUG, Logging.format(loggingContext.subject()
                                , "Creating " + this
                        )
                );
            }
        }

        public int getId() {
            return id;
        }

        public void incrementRmCount(int id) {
            this.itemArray[idSchema.fetchObjectId(id)] = null;
//            INDEX_UPDATER.decrementAndGet(this);
            rmCount.incrementAndGet();
            tenant.idStack.push(id);
        }

        public void decrementRmCount() {
            rmCount.decrementAndGet();
        }

        public int incrementIndex() {
            return INDEX_UPDATER.incrementAndGet(this);
        }

        @SuppressWarnings("unchecked")
        public T get(int id) {
            return (T) itemArray[idSchema.fetchObjectId(id)];
        }

        @SuppressWarnings("unchecked")
        public T set(T value, Object[] data) {
            int idx = idSchema.fetchObjectId(idUpdater.getId(value));
            if (dataLength == 1) {
                dataArray[idx] = data[0];
            } else if (dataLength > 1) {
                for (int i = 0; i < dataLength; i++) {
                    multiDataArray[i][idx] = data[i];
                }
            }
            return (T) (itemArray[idx] = value);
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public void copy(T value, LinkedChunk<T> prevChunk, int newId, int[] indexMapping) {
            int prevIdx = idSchema.fetchObjectId(value.getId());
            int newIdx = idSchema.fetchObjectId(newId);
            if (indexMapping.length > 0) {
                if (dataLength == 1) { // copy to new dataArray
                    if (prevChunk.dataLength == 1) { // copy from prev.dataArray
                        dataArray[newIdx] = prevChunk.dataArray[prevIdx];
                    } else { // copy from prev.multiDataArray
                        int i = -1;
                        while (indexMapping[++i] != 0) ;
                        dataArray[newIdx] = prevChunk.multiDataArray[i][prevIdx];
                    }
                } else { // copy to new multiDataArray
                    if (prevChunk.dataLength == 1) { // copy from prev.dataArray
                        if (indexMapping[0] > -1) {
                            multiDataArray[indexMapping[0]][newIdx] = prevChunk.dataArray[prevIdx];
                        }
                    } else {  // copy from prev.multiDataArray
                        int i = -1;
                        while (indexMapping[++i] < 0) ;
                        i--;
                        while (++i < indexMapping.length) {
                            if (indexMapping[i] > -1) {
                                multiDataArray[indexMapping[i]][newIdx] = prevChunk.multiDataArray[i][prevIdx];
                            }
                        }
                    }
                }
            }
            idUpdater.setId(value, newId);
            itemArray[newIdx] = value;
        }

        public void add(int id, int[] addedIndexMapping, Object addedComponent, Object[] addedComponents) {
            int idx = idSchema.fetchObjectId(id);
            if (dataLength == 1) { // add to dataArray
                if (addedComponent != null) {
                    dataArray[idx] = addedComponent;
                } else {
                    for (int i = 0; i < addedIndexMapping.length; i++) {
                        if (addedIndexMapping[i] == 0) {
                            dataArray[idx] = addedComponents[i];
                        }
                    }
                }
            } else if (dataLength > 1) { // add to multiDataArray
                if (addedComponent != null) {
                    multiDataArray[addedIndexMapping[0]][idx] = addedComponent;
                } else {
                    for (int i = 0; i < addedIndexMapping.length; i++) {
                        if (addedIndexMapping[i] > -1) {
                            multiDataArray[addedIndexMapping[i]][idx] = addedComponents[i];
                        }
                    }
                }
            }
        }

        public Object[] getData(int id) {
            int idx = idSchema.fetchObjectId(id);
            Object[] data = new Object[dataLength];
            if (dataLength == 1) {
                data[0] = dataArray[idx];
            }
            if (dataLength > 1) {
                for (int i = 0; i < dataLength; i++) {
                    data[i] = multiDataArray[i][idx];
                }
            }
            return data;
        }

        public Object getFromDataArray(int id) {
            return dataArray[idSchema.fetchObjectId(id)];
        }

        public Object getFromMultiDataArray(int id, int i) {
            return multiDataArray[i][idSchema.fetchObjectId(id)];
        }

        public Tenant<T> getTenant() {
            return tenant;
        }

        public int getDataLength() {
            return dataLength;
        }

        public boolean hasCapacity() {
            return index < idSchema.chunkCapacity - 1;
        }

        public LinkedChunk<T> getPrevious() {
            return previous;
        }

        private void setNext(LinkedChunk<T> next) {
            this.next = next;
            sizeOffset = 1;
        }

        public int size() {
            return index + sizeOffset - rmCount.get();
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public String toString() {
            return "LinkedChunk={"
                    + "id=" + id
                    + ", dataLength=" + dataLength
                    + ", capacity=" + idSchema.chunkCapacity
                    + ", size=" + size()
                    + ", previous=" + (previous == null ? null : previous.id)
                    + ", next=" + (next == null ? null : next.id)
                    + ", of " + tenant
                    + '}';
        }
    }


    // ROOT iterator

    public static abstract class PoolIterator<T extends Item, R> implements Iterator<R> {
        protected int index;
        protected LinkedChunk<T> currentChunk;
        protected IdSchema idSchema;

        public PoolIterator(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            this.currentChunk = currentChunk;
            this.idSchema = idSchema;
            this.index = currentChunk == null ? 0 : currentChunk.size();
            advance();
        }

        public void advance() {
            if (currentChunk == null) return;
            for (; ; ) {
                while (--index > -1) {
                    if (currentChunk.itemArray[index] != null) {
                        return;
                    }
                }
                if ((currentChunk = currentChunk.next) != null && !currentChunk.isEmpty()) {
                    index = currentChunk.size();
                } else {
                    return;
                }
            }
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        public boolean hasNext() {
            return index > -1;
        }

        @SuppressWarnings({"unchecked"})
        @Override
        public R next() {
            final var item = currentChunk.itemArray[index];
            advance();
            return (R) item;
        }
    }

    // SIZE ITERATOR

    public static class PoolSizeIterator<T extends Item> extends PoolIterator<T, Integer> {
        public PoolSizeIterator(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public Integer next() {
            final var size = index + 1;
            index -= size;
            return size;
        }
    }

    public static class PoolSizeEmptyIterator<T extends Item> extends PoolSizeIterator<T> {
        public static final PoolSizeEmptyIterator INSTANCE = new PoolSizeEmptyIterator();

        public PoolSizeEmptyIterator() {
            super(null, null);
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Integer next() {
            return 0;
        }
    }

    // ALL-ENTITIES ITERATOR

    public static class PoolAllEntitiesIterator<T extends ChunkedPool.Item> extends PoolIterator<T, T> {
        private final LinkedChunk<T>[] chunks;
        private int chunkIndex;

        public PoolAllEntitiesIterator(LinkedChunk<T>[] chunks, int chunkIndex) {
            super(chunks[chunkIndex], null);
            this.chunks = chunks;
            this.chunkIndex = chunkIndex;
            currentChunk = chunks[chunkIndex];
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        public boolean hasNext() {
            while (index > -1) {
                if (currentChunk.itemArray[index] != null) {
                    return true;
                }
                index--;
            }
            for (; ; ) {
                if (chunkIndex > 0 && (currentChunk = chunks[--chunkIndex]) != null) {
                    if (!currentChunk.isEmpty() && (index = currentChunk.size() - 1) == index) {
                        return true;
                    }
                } else {
                    return false;
                }
            }
        }
    }


    // SINGLE-DATA ITERATORS

    public static class PoolDataIterator<T extends Item> extends PoolIterator<T, T> {
        public PoolDataIterator(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        public Object data(int i) {
            return currentChunk.dataArray[index];
        }

        public Object next(PoolIteratorNextWith1 nextWith1, int i1) {
            return null;
        }

        public Object next(PoolIteratorNextWith2 nextWith2, int i1, int i2) {
            return null;
        }

        public Object next(PoolIteratorNextWith3 nextWith3, int i1, int i2, int i3) {
            return null;
        }

        public Object next(PoolIteratorNextWith4 nextWith4, int i1, int i2, int i3, int i4) {
            return null;
        }

        public Object next(PoolIteratorNextWith5 nextWith5, int i1, int i2, int i3, int i4, int i5) {
            return null;
        }

        public Object next(PoolIteratorNextWith6 nextWith6, int i1, int i2, int i3, int i4, int i5, int i6) {
            return null;
        }
    }

    public static class PoolDataEmptyIterator<T extends Item> extends PoolDataIterator<T> {
        public PoolDataEmptyIterator() {
            super(null, null);
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public T next() {
            return null;
        }

        @Override
        public Object data(int i) {
            return null;
        }
    }

    public static class PoolDataIteratorWithState<T extends Item> extends PoolDataIterator<T> {
        public PoolDataIteratorWithState(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        public Object next(PoolIteratorNextWith1 nextWith1, int i1) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            var itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith1.fetchNext(itemChunk.dataArray, itemIdx, next());
        }

        @Override
        public Object data(int i) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            var itemIdx = idSchema.fetchObjectId(item.getId());
            return itemChunk.dataArray[itemIdx];
        }
    }

    public static final class PoolDataNoItemIterator<T extends Item> extends PoolDataIterator<T> {
        public PoolDataNoItemIterator(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public T next() {
            advance();
            return null;
        }
    }

    public static final class PoolDataNoItemIteratorWithState<T extends Item> extends PoolDataIteratorWithState<T> {
        public PoolDataNoItemIteratorWithState(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public T next() {
            advance();
            return null;
        }
    }


    // MULTI-DATA ITERATORS

    public static class PoolMultiDataIterator<T extends Item> extends PoolDataIterator<T> {
        public PoolMultiDataIterator(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public Object data(int i) {
            return currentChunk.multiDataArray[i][index];
        }
    }

    public static class PoolMultiDataIteratorWithState<T extends Item> extends PoolDataIteratorWithState<T> {
        public PoolMultiDataIteratorWithState(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public Object next(PoolIteratorNextWith1 nextWith1, int i1) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            int itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith1.fetchNext(itemChunk.multiDataArray, i1, itemIdx, next());
        }

        @Override
        public Object next(PoolIteratorNextWith2 nextWith2, int i1, int i2) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            int itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith2.fetchNext(itemChunk.multiDataArray, i1, i2, itemIdx, next());
        }

        @Override
        public Object next(PoolIteratorNextWith3 nextWith3, int i1, int i2, int i3) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            int itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith3.fetchNext(itemChunk.multiDataArray, i1, i2, i3, itemIdx, next());
        }

        @Override
        public Object next(PoolIteratorNextWith4 nextWith4, int i1, int i2, int i3, int i4) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            int itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith4.fetchNext(itemChunk.multiDataArray, i1, i2, i3, i4, itemIdx, next());
        }

        @Override
        public Object next(PoolIteratorNextWith5 nextWith5, int i1, int i2, int i3, int i4, int i5) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            int itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith5.fetchNext(itemChunk.multiDataArray, i1, i2, i3, i4, i5, itemIdx, next());
        }

        @Override
        public Object next(PoolIteratorNextWith6 nextWith6, int i1, int i2, int i3, int i4, int i5, int i6) {
            var item = currentChunk.itemArray[index];
            var itemChunk = item.getChunk();
            int itemIdx = idSchema.fetchObjectId(item.getId());
            return nextWith6.fetchNext(itemChunk.multiDataArray, i1, i2, i3, i4, i5, i6, itemIdx, next());
        }
    }

    public static final class PoolMultiDataNoItemIterator<T extends Item> extends PoolMultiDataIterator<T> {
        public PoolMultiDataNoItemIterator(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public T next() {
            advance();
            return null;
        }
    }

    public static final class PoolMultiDataNoItemIteratorWithState<T extends Item> extends PoolMultiDataIteratorWithState<T> {
        public PoolMultiDataNoItemIteratorWithState(LinkedChunk<T> currentChunk, IdSchema idSchema) {
            super(currentChunk, idSchema);
        }

        @Override
        public T next() {
            advance();
            return null;
        }
    }
}
