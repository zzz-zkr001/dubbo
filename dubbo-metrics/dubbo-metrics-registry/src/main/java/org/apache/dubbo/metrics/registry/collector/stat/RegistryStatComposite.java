/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.metrics.registry.collector.stat;

import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.metrics.collector.MetricsCollector;
import org.apache.dubbo.metrics.model.ApplicationMetric;
import org.apache.dubbo.metrics.model.MetricsCategory;
import org.apache.dubbo.metrics.model.MetricsKey;
import org.apache.dubbo.metrics.model.MetricsKeyWrapper;
import org.apache.dubbo.metrics.model.container.AtomicLongContainer;
import org.apache.dubbo.metrics.model.container.LongAccumulatorContainer;
import org.apache.dubbo.metrics.model.container.LongContainer;
import org.apache.dubbo.metrics.model.sample.GaugeMetricSample;
import org.apache.dubbo.metrics.registry.event.RegistryEvent;
import org.apache.dubbo.metrics.report.MetricsExport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.stream.Collectors;

/**
 * As a data aggregator, use internal data containers calculates and classifies
 * the registry data collected by {@link MetricsCollector MetricsCollector}, and
 * provides an {@link MetricsExport MetricsExport} interface for exporting standard output formats.
 */
public class RegistryStatComposite implements MetricsExport {


    public Map<RegistryEvent.Type, Map<String, AtomicLong>> numStats = new ConcurrentHashMap<>();
    public Map<RegistryEvent.Type, Map<ServiceKeyMetric, AtomicLong>> skStats = new ConcurrentHashMap<>();
    public List<LongContainer<? extends Number>> rtStats = new ArrayList<>();
    public static String OP_TYPE_REGISTER = "register";
    public static String OP_TYPE_SUBSCRIBE = "subscribe";
    public static String OP_TYPE_NOTIFY = "notify";

    public RegistryStatComposite() {
        for (RegistryEvent.Type type : RegistryEvent.Type.values()) {
            numStats.put(type, new ConcurrentHashMap<>());
        }

        rtStats.addAll(initStats(OP_TYPE_REGISTER));
        rtStats.addAll(initStats(OP_TYPE_SUBSCRIBE));
        rtStats.addAll(initStats(OP_TYPE_NOTIFY));
    }

    private List<LongContainer<? extends Number>> initStats(String registryOpType) {
        List<LongContainer<? extends Number>> singleRtStats = new ArrayList<>();
        singleRtStats.add(new AtomicLongContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_LAST)));
        singleRtStats.add(new LongAccumulatorContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_MIN), new LongAccumulator(Long::min, Long.MAX_VALUE)));
        singleRtStats.add(new LongAccumulatorContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_MAX), new LongAccumulator(Long::max, Long.MIN_VALUE)));
        singleRtStats.add(new AtomicLongContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_SUM), (responseTime, longAccumulator) -> longAccumulator.addAndGet(responseTime)));
        // AvgContainer is a special counter that stores the number of times but outputs function of sum/times
        AtomicLongContainer avgContainer = new AtomicLongContainer(new MetricsKeyWrapper(registryOpType, MetricsKey.METRIC_RT_AVG), (k, v) -> v.incrementAndGet());
        avgContainer.setValueSupplier(applicationName -> {
            LongContainer<? extends Number> totalContainer = rtStats.stream().filter(longContainer -> longContainer.isKeyWrapper(MetricsKey.METRIC_RT_SUM, registryOpType)).findFirst().get();
            AtomicLong totalRtTimes = avgContainer.get(applicationName);
            AtomicLong totalRtSum = (AtomicLong) totalContainer.get(applicationName);
            return totalRtSum.get() / totalRtTimes.get();
        });
        singleRtStats.add(avgContainer);
        return singleRtStats;
    }

    public void setApplicationKey(RegistryEvent.Type type, String applicationName, int num) {
        if (!numStats.containsKey(type)) {
            return;
        }
        numStats.get(type).computeIfAbsent(applicationName, k -> new AtomicLong(0L)).set(num);
    }

    public void setServiceKey(RegistryEvent.Type type, String applicationName, String serviceKey, int num) {
        if (!skStats.containsKey(type)) {
            return;
        }
        skStats.get(type).computeIfAbsent(new ServiceKeyMetric(applicationName, serviceKey), k -> new AtomicLong(0L)).set(num);
    }

    public void increment(RegistryEvent.Type type, String applicationName) {
        if (!numStats.containsKey(type)) {
            return;
        }
        numStats.get(type).computeIfAbsent(applicationName, k -> new AtomicLong(0L)).incrementAndGet();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void calcRt(String applicationName, String registryOpType, Long responseTime) {
        for (LongContainer container : rtStats.stream().filter(longContainer -> longContainer.specifyType(registryOpType)).collect(Collectors.toList())) {
            Number current = (Number) ConcurrentHashMapUtils.computeIfAbsent(container, applicationName, container.getInitFunc());
            container.getConsumerFunc().accept(responseTime, current);
        }
    }

    @Override
    @SuppressWarnings({"rawtypes"})
    public List<GaugeMetricSample> exportNumMetrics() {
        List<GaugeMetricSample> list = new ArrayList<>();
        for (RegistryEvent.Type type : numStats.keySet()) {
            Map<String, AtomicLong> stringAtomicLongMap = numStats.get(type);
            for (String applicationName : stringAtomicLongMap.keySet()) {
                list.add(convertToSample(applicationName, type, MetricsCategory.REGISTRY, stringAtomicLongMap.get(applicationName)));
            }
        }
        return list;
    }

    @Override
    @SuppressWarnings({"rawtypes"})
    public List<GaugeMetricSample> exportRtMetrics() {
        List<GaugeMetricSample> list = new ArrayList<>();
        for (LongContainer<? extends Number> rtContainer : rtStats) {
            MetricsKeyWrapper metricsKeyWrapper = rtContainer.getMetricsKeyWrapper();
            for (Map.Entry<String, ? extends Number> entry : rtContainer.entrySet()) {
                list.add(new GaugeMetricSample<>(metricsKeyWrapper.targetKey(), metricsKeyWrapper.targetDesc(), ApplicationMetric.getTagsByName(entry.getKey()), MetricsCategory.RT, entry.getKey().intern(), value -> rtContainer.getValueSupplier().apply(value.intern())));
            }
        }
        return list;
    }

    @SuppressWarnings({"rawtypes"})
    public List<GaugeMetricSample> exportSkMetrics() {
        List<GaugeMetricSample> list = new ArrayList<>();
        for (RegistryEvent.Type type : skStats.keySet()) {
            Map<ServiceKeyMetric, AtomicLong> stringAtomicLongMap = skStats.get(type);
            for (ServiceKeyMetric serviceKeyMetric : stringAtomicLongMap.keySet()) {
                list.add(new GaugeMetricSample<>(type.getMetricsKey(), serviceKeyMetric.getTags(), MetricsCategory.REGISTRY, stringAtomicLongMap, value -> value.get(serviceKeyMetric).get()));
            }
        }
        return list;
    }

    @SuppressWarnings({"rawtypes"})
    public GaugeMetricSample convertToSample(String applicationName, RegistryEvent.Type type, MetricsCategory category, AtomicLong targetNumber) {
        return new GaugeMetricSample<>(type.getMetricsKey(), ApplicationMetric.getTagsByName(applicationName), category, targetNumber, AtomicLong::get);
    }
}
