/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import io.opentelemetry.sdk.metrics.aggregator.AggregatorFactory;
import io.opentelemetry.sdk.metrics.common.InstrumentDescriptor;
import io.opentelemetry.sdk.metrics.common.InstrumentType;
import io.opentelemetry.sdk.metrics.view.InstrumentSelector;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Central location for Views to be registered. Registration of a view should eventually be done via
 * the {@link SdkMeterProvider}.
 *
 * <p>This class uses copy-on-write for the registered views to ensure that reading threads get
 * never blocked.
 */
final class ViewRegistry {
  private static final LinkedHashMap<Pattern, AggregatorFactory> EMPTY_CONFIG =
      new LinkedHashMap<>();
  static final AggregatorFactory CUMULATIVE_SUM = AggregatorFactory.sum(true);
  static final AggregatorFactory SUMMARY = AggregatorFactory.minMaxSumCount();
  static final AggregatorFactory LAST_VALUE = AggregatorFactory.lastValue();

  // The lock is used to ensure only one updated to the configuration happens at any moment.
  private final ReentrantLock lock = new ReentrantLock();
  private volatile EnumMap<InstrumentType, LinkedHashMap<Pattern, AggregatorFactory>> configuration;

  ViewRegistry() {
    this.configuration = new EnumMap<>(InstrumentType.class);
    configuration.put(InstrumentType.COUNTER, EMPTY_CONFIG);
    configuration.put(InstrumentType.UP_DOWN_COUNTER, EMPTY_CONFIG);
    configuration.put(InstrumentType.VALUE_RECORDER, EMPTY_CONFIG);
    configuration.put(InstrumentType.SUM_OBSERVER, EMPTY_CONFIG);
    configuration.put(InstrumentType.UP_DOWN_SUM_OBSERVER, EMPTY_CONFIG);
    configuration.put(InstrumentType.VALUE_OBSERVER, EMPTY_CONFIG);
  }

  void registerView(InstrumentSelector selector, AggregatorFactory aggregatorFactory) {
    lock.lock();
    try {
      EnumMap<InstrumentType, LinkedHashMap<Pattern, AggregatorFactory>> newConfiguration =
          new EnumMap<>(configuration);
      newConfiguration.put(
          selector.getInstrumentType(),
          newLinkedHashMap(
              selector.getInstrumentNamePattern(),
              aggregatorFactory,
              newConfiguration.get(selector.getInstrumentType())));
      configuration = newConfiguration;
    } finally {
      lock.unlock();
    }
  }

  AggregatorFactory findView(InstrumentDescriptor descriptor) {
    LinkedHashMap<Pattern, AggregatorFactory> configPerType =
        configuration.get(descriptor.getType());
    for (Map.Entry<Pattern, AggregatorFactory> entry : configPerType.entrySet()) {
      if (entry.getKey().matcher(descriptor.getName()).matches()) {
        return entry.getValue();
      }
    }

    return getDefaultSpecification(descriptor);
  }

  private static AggregatorFactory getDefaultSpecification(InstrumentDescriptor descriptor) {
    switch (descriptor.getType()) {
      case COUNTER:
      case UP_DOWN_COUNTER:
      case SUM_OBSERVER:
      case UP_DOWN_SUM_OBSERVER:
        return CUMULATIVE_SUM;
      case VALUE_RECORDER:
        return SUMMARY;
      case VALUE_OBSERVER:
        return LAST_VALUE;
    }
    throw new IllegalArgumentException("Unknown descriptor type: " + descriptor.getType());
  }

  private static LinkedHashMap<Pattern, AggregatorFactory> newLinkedHashMap(
      Pattern pattern,
      AggregatorFactory aggregatorFactory,
      LinkedHashMap<Pattern, AggregatorFactory> parentConfiguration) {
    LinkedHashMap<Pattern, AggregatorFactory> result = new LinkedHashMap<>();
    result.put(pattern, aggregatorFactory);
    result.putAll(parentConfiguration);
    return result;
  }
}
