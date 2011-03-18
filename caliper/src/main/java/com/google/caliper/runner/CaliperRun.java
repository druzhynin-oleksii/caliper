/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.caliper.runner;

import com.google.caliper.api.Benchmark;
import com.google.caliper.api.SkipThisScenarioException;
import com.google.caliper.util.InvalidCommandException;
import com.google.caliper.util.Util;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

import java.text.ParseException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/**
 * A single execution of the benchmark runner, for a particular set of options.
 */
public final class CaliperRun {
  private final CaliperOptions options;
  private final CaliperRc caliperRc;
  private final ConsoleWriter console;
  private final BenchmarkClass benchmarkClass;
  private final ImmutableSetMultimap<String, Object> userParameters;
  private final Collection<BenchmarkMethod> methods;

  public CaliperRun(CaliperOptions options, CaliperRc caliperRc, ConsoleWriter console)
      throws InvalidCommandException, InvalidBenchmarkException {
    this.options = options;
    this.caliperRc = caliperRc;
    this.console = console;

    Class<?> aClass = classForName(options.benchmarkClassName());
    this.benchmarkClass = new BenchmarkClass(aClass);
    this.userParameters = convertUserParameters(options.userParameters(), benchmarkClass);
    this.methods = findBenchmarkMethods(benchmarkClass, options);
  }

  public void run() throws UserCodeException {
    ImmutableList<VirtualMachine> vms = options.vms();

    ImmutableSetMultimap<String, Object> combinedParams =
        benchmarkClass.userParameters().fillInDefaultsFor(userParameters);

    ImmutableSetMultimap<String, String> vmArguments =
        benchmarkClass.injectableVmArguments().fillInDefaultsFor(options.vmArguments());

    // TODO(kevinb): other kinds of partial scenario selectors...
    ScenarioSelection selection = new FullCartesianScenarioSelection(
        methods, vms, combinedParams, vmArguments);

    console.describe(selection);

    Set<Scenario> mutableScenarios = Sets.newHashSet(selection.buildScenarios());

    console.beforeDryRun(mutableScenarios.size());
    console.flush();

    // always dry run first.
    dryRun(/*INOUT*/mutableScenarios);

    int finalScenarioCount = mutableScenarios.size();
    int estimate;
    try {
      estimate = options.instrument().estimateRuntimeSeconds(finalScenarioCount, options);
    } catch (Exception e) {
      estimate = 0;
    }
    console.beforeRun(options.trials(), finalScenarioCount, estimate);
    console.flush();

    if (options.dryRun()) {
      return;
    }

    // TODO(kevinb): now the wet run!
  }

  // Simply converts all the strings to the right datatypes
  private static ImmutableSetMultimap<String, Object> convertUserParameters(
      Multimap<String, String> rawValues, BenchmarkClass benchmarkClass
  ) throws InvalidCommandException {
    ImmutableSetMultimap.Builder<String, Object> builder = ImmutableSetMultimap.builder();
    builder.orderKeysBy(Ordering.natural());

    for (String paramName : rawValues.keySet()) {
      Parameter p = benchmarkClass.userParameters().get(paramName);
      if (p == null) {
        throw new InvalidCommandException("unrecognized parameter: " + paramName);
      }

      for (String valueAsString : rawValues.get(paramName)) {
        try {
          Object value = p.parser().parse(valueAsString);
          builder.put(paramName, value);
        } catch (ParseException e) {
          throw new InvalidCommandException("Couldn't parse value '%s' for parameter '%s' of type '%s': %s",
              valueAsString, paramName, p.type(), e.getMessage());
        }
      }
    }
    return builder.build();
  }

  public static Collection<BenchmarkMethod> findBenchmarkMethods(
      BenchmarkClass benchmarkClass, CaliperOptions options)
      throws InvalidBenchmarkException {
    Instrument instrument = options.instrument();
    ImmutableMap<String, BenchmarkMethod> methodMap =
        benchmarkClass.findAllBenchmarkMethods(instrument);

    ImmutableSet<String> names = options.benchmarkMethodNames();

    // TODO(kevinb): this doesn't seem to prevent bogus names on cmd line yet
    return names.isEmpty()
        ? methodMap.values()
        : Maps.filterKeys(methodMap, Predicates.in(names)).values();
  }

  public void dryRun(Set<Scenario> mutableScenarios) throws UserCodeException {
    Instrument instrument = options.instrument();

    Iterator<Scenario> it = mutableScenarios.iterator();
    while (it.hasNext()) {
      Scenario scenario = it.next();
      try {
        Benchmark benchmark = benchmarkClass.createAndStage(scenario);
        instrument.dryRun(benchmark, scenario.benchmarkMethod());
      } catch (SkipThisScenarioException innocuous) {
        it.remove();
      }
    }
  }

  private static Class<?> classForName(String className)
      throws InvalidCommandException, InvalidBenchmarkException {
    try {
      return Util.lenientClassForName(className);
    } catch (ClassNotFoundException e) {
      throw new InvalidCommandException("Benchmark class not found: " + className);
    } catch (ExceptionInInitializerError e) {
      throw new UserCodeException(
          "Exception thrown while initializing class '" + className + "'", e.getCause());
    }
  }
}