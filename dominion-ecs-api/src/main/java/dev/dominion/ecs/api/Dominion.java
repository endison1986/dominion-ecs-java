/*
 * Copyright (c) 2021 Enrico Stara
 * This code is licensed under the MIT license. See the LICENSE file in the project root for license terms.
 */

package dev.dominion.ecs.api;

import java.util.NoSuchElementException;
import java.util.ServiceLoader;

public interface Dominion extends AutoCloseable {

    static Dominion create() {
        return factory().create();
    }

    static Dominion create(String name) {
        return factory().create(name);
    }

    static Dominion.Factory factory() {
        return factory("dev.dominion.ecs.engine");
    }

    static Dominion.Factory factory(String implementation) {
        return ServiceLoader
                .load(Dominion.Factory.class)
                .stream()
                .filter(p -> p.get().getClass().getName().contains(implementation))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unable to load " + implementation))
                .get();
    }

    String getName();

    Entity createEntity(Object... components);

    Entity createEntityAs(Entity prefab, Object... components);

    boolean deleteEntity(Entity entity);

    <T> Results<Results.Comp1<T>> findComponents(Class<T> type);

    <T1, T2> Results<Results.Comp2<T1, T2>> findComponents(Class<T1> type1, Class<T2> type2);

    <T1, T2, T3> Results<Results.Comp3<T1, T2, T3>> findComponents(Class<T1> type1, Class<T2> type2, Class<T3> type3);

    <T1, T2, T3, T4> Results<Results.Comp4<T1, T2, T3, T4>> findComponents(Class<T1> type1, Class<T2> type2, Class<T3> type3, Class<T4> type4);

    <T1, T2, T3, T4, T5> Results<Results.Comp5<T1, T2, T3, T4, T5>> findComponents(Class<T1> type1, Class<T2> type2, Class<T3> type3, Class<T4> type4, Class<T5> type5);

    <T1, T2, T3, T4, T5, T6> Results<Results.Comp6<T1, T2, T3, T4, T5, T6>> findComponents(Class<T1> type1, Class<T2> type2, Class<T3> type3, Class<T4> type4, Class<T5> type5, Class<T6> type6);

    interface Factory {
        Dominion create();

        Dominion create(String name);
    }
}
