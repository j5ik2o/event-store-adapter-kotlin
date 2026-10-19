package com.github.j5ik2o.event.store.adapter.java.memory;

import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.lang.reflect.Field;

/**
 * Test double for the conformance runner's own tests: a storage that shares the records and the
 * lock of another storage but keeps its own default configuration (no retention).
 *
 * <p>The public API cannot build this state ({@code policy} and the shared fields are
 * package-private and final), so the shared fields are replaced by reflection.
 */
public final class DefaultConfigurationStorageView {
  private DefaultConfigurationStorageView() {}

  public static MemoryStorage of(MemoryStorage shared) {
    MemoryStorage view = MemoryStorage.create(RetentionPolicy.none(), shared.hooks);
    replace(view, "aggregates", shared.aggregates);
    replace(view, "lock", shared.lock);
    return view;
  }

  private static void replace(MemoryStorage target, String name, Object value) {
    try {
      Field field = MemoryStorage.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot share " + name, e);
    }
  }
}
