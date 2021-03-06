/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.spark.process.computer;

import org.apache.commons.configuration.ConfigurationUtils;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.spark.HashPartitioner;
import org.apache.spark.Partitioner;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.launcher.SparkLauncher;
import org.apache.spark.storage.StorageLevel;
import org.apache.tinkerpop.gremlin.hadoop.Constants;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.AbstractHadoopGraphComputer;
import org.apache.tinkerpop.gremlin.hadoop.process.computer.util.ComputerSubmissionHelper;
import org.apache.tinkerpop.gremlin.hadoop.structure.HadoopConfiguration;
import org.apache.tinkerpop.gremlin.hadoop.structure.HadoopGraph;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.FileSystemStorage;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.GraphFilterAware;
import org.apache.tinkerpop.gremlin.hadoop.structure.io.VertexWritable;
import org.apache.tinkerpop.gremlin.hadoop.structure.util.ConfUtil;
import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.MapReduce;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.VertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.util.DefaultComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.util.MapMemory;
import org.apache.tinkerpop.gremlin.spark.process.computer.payload.ViewIncomingPayload;
import org.apache.tinkerpop.gremlin.spark.structure.Spark;
import org.apache.tinkerpop.gremlin.spark.structure.io.InputFormatRDD;
import org.apache.tinkerpop.gremlin.spark.structure.io.InputOutputHelper;
import org.apache.tinkerpop.gremlin.spark.structure.io.InputRDD;
import org.apache.tinkerpop.gremlin.spark.structure.io.OutputFormatRDD;
import org.apache.tinkerpop.gremlin.spark.structure.io.OutputRDD;
import org.apache.tinkerpop.gremlin.spark.structure.io.PersistedInputRDD;
import org.apache.tinkerpop.gremlin.spark.structure.io.PersistedOutputRDD;
import org.apache.tinkerpop.gremlin.spark.structure.io.SparkContextStorage;
import org.apache.tinkerpop.gremlin.spark.structure.io.gryo.GryoSerializer;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.io.Storage;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class SparkGraphComputer extends AbstractHadoopGraphComputer {

    private final org.apache.commons.configuration.Configuration sparkConfiguration;
    private boolean workersSet = false;

    public SparkGraphComputer(final HadoopGraph hadoopGraph) {
        super(hadoopGraph);
        this.sparkConfiguration = new HadoopConfiguration();
        ConfigurationUtils.copy(this.hadoopGraph.configuration(), this.sparkConfiguration);
    }

    @Override
    public GraphComputer workers(final int workers) {
        super.workers(workers);
        if (this.sparkConfiguration.containsKey(SparkLauncher.SPARK_MASTER) && this.sparkConfiguration.getString(SparkLauncher.SPARK_MASTER).startsWith("local")) {
            this.sparkConfiguration.setProperty(SparkLauncher.SPARK_MASTER, "local[" + this.workers + "]");
        }
        this.workersSet = true;
        return this;
    }

    @Override
    public GraphComputer configure(final String key, final Object value) {
        this.sparkConfiguration.setProperty(key, value);
        return this;
    }

    @Override
    public Future<ComputerResult> submit() {
        this.validateStatePriorToExecution();
        return ComputerSubmissionHelper.runWithBackgroundThread(this::submitWithExecutor, "SparkSubmitter");
    }

    private Future<ComputerResult> submitWithExecutor(Executor exec) {
        // create the completable future                                                    
        return CompletableFuture.<ComputerResult>supplyAsync(() -> {
            final long startTime = System.currentTimeMillis();
            // apache and hadoop configurations that are used throughout the graph computer computation
            final org.apache.commons.configuration.Configuration apacheConfiguration = new HadoopConfiguration(this.sparkConfiguration);
            if (!apacheConfiguration.containsKey(Constants.SPARK_SERIALIZER))
                apacheConfiguration.setProperty(Constants.SPARK_SERIALIZER, GryoSerializer.class.getCanonicalName());
            apacheConfiguration.setProperty(Constants.GREMLIN_HADOOP_GRAPH_WRITER_HAS_EDGES, this.persist.equals(GraphComputer.Persist.EDGES));
            final Configuration hadoopConfiguration = ConfUtil.makeHadoopConfiguration(apacheConfiguration);
            final Storage fileSystemStorage = FileSystemStorage.open(hadoopConfiguration);
            final Storage sparkContextStorage = SparkContextStorage.open(apacheConfiguration);
            final boolean inputFromHDFS = FileInputFormat.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_READER, Object.class));
            final boolean inputFromSpark = PersistedInputRDD.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_READER, Object.class));
            final boolean outputToHDFS = FileOutputFormat.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_WRITER, Object.class));
            final boolean outputToSpark = PersistedOutputRDD.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_WRITER, Object.class));
            String inputLocation = null;
            if (inputFromSpark)
                inputLocation = Constants.getSearchGraphLocation(hadoopConfiguration.get(Constants.GREMLIN_HADOOP_INPUT_LOCATION), sparkContextStorage).orElse(null);
            else if (inputFromHDFS)
                inputLocation = Constants.getSearchGraphLocation(hadoopConfiguration.get(Constants.GREMLIN_HADOOP_INPUT_LOCATION), fileSystemStorage).orElse(null);
            if (null == inputLocation)
                inputLocation = hadoopConfiguration.get(Constants.GREMLIN_HADOOP_INPUT_LOCATION);

            if (null != inputLocation && inputFromHDFS) {
                try {
                    apacheConfiguration.setProperty(Constants.MAPREDUCE_INPUT_FILEINPUTFORMAT_INPUTDIR, FileSystem.get(hadoopConfiguration).getFileStatus(new Path(inputLocation)).getPath().toString());
                    hadoopConfiguration.set(Constants.MAPREDUCE_INPUT_FILEINPUTFORMAT_INPUTDIR, FileSystem.get(hadoopConfiguration).getFileStatus(new Path(inputLocation)).getPath().toString());
                } catch (final IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            final InputRDD inputRDD;
            final OutputRDD outputRDD;
            final boolean filtered;
            try {

                inputRDD = InputRDD.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_READER, Object.class)) ?
                        hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_READER, InputRDD.class, InputRDD.class).newInstance() :
                        InputFormatRDD.class.newInstance();
                outputRDD = OutputRDD.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_WRITER, Object.class)) ?
                        hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_WRITER, OutputRDD.class, OutputRDD.class).newInstance() :
                        OutputFormatRDD.class.newInstance();
                // if the input class can filter on load, then set the filters
                if (inputRDD instanceof InputFormatRDD && GraphFilterAware.class.isAssignableFrom(hadoopConfiguration.getClass(Constants.GREMLIN_HADOOP_GRAPH_READER, InputFormat.class, InputFormat.class))) {
                    GraphFilterAware.storeGraphFilter(apacheConfiguration, hadoopConfiguration, this.graphFilter);
                    filtered = false;
                } else if (inputRDD instanceof GraphFilterAware) {
                    ((GraphFilterAware) inputRDD).setGraphFilter(this.graphFilter);
                    filtered = false;
                } else if (this.graphFilter.hasFilter()) {
                    filtered = true;
                } else {
                    filtered = false;
                }
            } catch (final InstantiationException | IllegalAccessException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

            SparkMemory memory = null;
            // delete output location
            final String outputLocation = hadoopConfiguration.get(Constants.GREMLIN_HADOOP_OUTPUT_LOCATION, null);
            if (null != outputLocation) {
                if (outputToHDFS && fileSystemStorage.exists(outputLocation))
                    fileSystemStorage.rm(outputLocation);
                if (outputToSpark && sparkContextStorage.exists(outputLocation))
                    sparkContextStorage.rm(outputLocation);
            }

            // the Spark application name will always be set by SparkContextStorage, thus, INFO the name to make it easier to debug
            logger.debug(Constants.GREMLIN_HADOOP_SPARK_JOB_PREFIX + (null == this.vertexProgram ? "No VertexProgram" : this.vertexProgram) + "[" + this.mapReducers + "]");

            // create the spark configuration from the graph computer configuration
            final SparkConf sparkConfiguration = new SparkConf();
            hadoopConfiguration.forEach(entry -> sparkConfiguration.set(entry.getKey(), entry.getValue()));
            // execute the vertex program and map reducers and if there is a failure, auto-close the spark context
            try {
                final JavaSparkContext sparkContext = new JavaSparkContext(SparkContext.getOrCreate(sparkConfiguration));
                this.loadJars(sparkContext, hadoopConfiguration); // add the project jars to the cluster
                Spark.create(sparkContext.sc()); // this is the context RDD holder that prevents GC
                updateLocalConfiguration(sparkContext, sparkConfiguration);
                // create a message-passing friendly rdd from the input rdd
                JavaPairRDD<Object, VertexWritable> computedGraphRDD = null;
                boolean partitioned = false;
                JavaPairRDD<Object, VertexWritable> loadedGraphRDD = inputRDD.readGraphRDD(apacheConfiguration, sparkContext);
                // if there are vertex or edge filters, filter the loaded graph rdd prior to partitioning and persisting
                if (filtered) {
                    this.logger.debug("Filtering the loaded graphRDD: " + this.graphFilter);
                    loadedGraphRDD = SparkExecutor.applyGraphFilter(loadedGraphRDD, this.graphFilter);
                }
                // if the loaded graph RDD is already partitioned use that partitioner, else partition it with HashPartitioner
                if (loadedGraphRDD.partitioner().isPresent())
                    this.logger.debug("Using the existing partitioner associated with the loaded graphRDD: " + loadedGraphRDD.partitioner().get());
                else {
                    final Partitioner partitioner = new HashPartitioner(this.workersSet ? this.workers : loadedGraphRDD.partitions().size());
                    this.logger.debug("Partitioning the loaded graphRDD: " + partitioner);
                    loadedGraphRDD = loadedGraphRDD.partitionBy(partitioner);
                    partitioned = true;
                }
                assert loadedGraphRDD.partitioner().isPresent();
                // if the loaded graphRDD was already partitioned previous, then this coalesce/repartition will not take place
                if (this.workersSet) {
                    if (loadedGraphRDD.partitions().size() > this.workers) // ensures that the loaded graphRDD does not have more partitions than workers
                        loadedGraphRDD = loadedGraphRDD.coalesce(this.workers);
                    else if (loadedGraphRDD.partitions().size() < this.workers) // ensures that the loaded graphRDD does not have less partitions than workers
                        loadedGraphRDD = loadedGraphRDD.repartition(this.workers);
                }
                // persist the vertex program loaded graph as specified by configuration or else use default cache() which is MEMORY_ONLY
                if (!inputFromSpark || partitioned || filtered)
                    loadedGraphRDD = loadedGraphRDD.persist(StorageLevel.fromString(hadoopConfiguration.get(Constants.GREMLIN_SPARK_GRAPH_STORAGE_LEVEL, "MEMORY_ONLY")));


                ////////////////////////////////
                // process the vertex program //
                ////////////////////////////////
                if (null != this.vertexProgram) {
                    // set up the vertex program and wire up configurations
                    JavaPairRDD<Object, ViewIncomingPayload<Object>> viewIncomingRDD = null;
                    memory = new SparkMemory(this.vertexProgram, this.mapReducers, sparkContext);
                    this.vertexProgram.setup(memory);
                    memory.broadcastMemory(sparkContext);
                    final HadoopConfiguration vertexProgramConfiguration = new HadoopConfiguration();
                    this.vertexProgram.storeState(vertexProgramConfiguration);
                    ConfigurationUtils.copy(vertexProgramConfiguration, apacheConfiguration);
                    ConfUtil.mergeApacheIntoHadoopConfiguration(vertexProgramConfiguration, hadoopConfiguration);
                    // execute the vertex program
                    while (true) {
                        memory.setInExecute(true);
                        viewIncomingRDD = SparkExecutor.executeVertexProgramIteration(loadedGraphRDD, viewIncomingRDD, memory, vertexProgramConfiguration);
                        memory.setInExecute(false);
                        if (this.vertexProgram.terminate(memory))
                            break;
                        else {
                            memory.incrIteration();
                            memory.broadcastMemory(sparkContext);
                        }
                    }
                    memory.complete(); // drop all transient memory keys
                    // write the computed graph to the respective output (rdd or output format)
                    computedGraphRDD = SparkExecutor.prepareFinalGraphRDD(loadedGraphRDD, viewIncomingRDD, this.vertexProgram.getVertexComputeKeys());
                    if (null != outputRDD && !this.persist.equals(Persist.NOTHING)) {
                        outputRDD.writeGraphRDD(apacheConfiguration, computedGraphRDD);
                    }
                }

                final boolean computedGraphCreated = computedGraphRDD != null;
                if (!computedGraphCreated)
                    computedGraphRDD = loadedGraphRDD;

                final Memory.Admin finalMemory = null == memory ? new MapMemory() : new MapMemory(memory);

                //////////////////////////////
                // process the map reducers //
                //////////////////////////////
                if (!this.mapReducers.isEmpty()) {
                    if (computedGraphCreated && !outputToSpark) {
                        // drop all the edges of the graph as they are not used in mapReduce processing
                        computedGraphRDD = computedGraphRDD.mapValues(vertexWritable -> {
                            vertexWritable.get().dropEdges(Direction.BOTH);
                            return vertexWritable;
                        });
                        // if there is only one MapReduce to execute, don't bother wasting the clock cycles.
                        if (this.mapReducers.size() > 1)
                            computedGraphRDD = computedGraphRDD.persist(StorageLevel.fromString(hadoopConfiguration.get(Constants.GREMLIN_SPARK_GRAPH_STORAGE_LEVEL, "MEMORY_ONLY")));
                    }

                    for (final MapReduce mapReduce : this.mapReducers) {
                        // execute the map reduce job
                        final HadoopConfiguration newApacheConfiguration = new HadoopConfiguration(apacheConfiguration);
                        mapReduce.storeState(newApacheConfiguration);
                        // map
                        final JavaPairRDD mapRDD = SparkExecutor.executeMap((JavaPairRDD) computedGraphRDD, mapReduce, newApacheConfiguration);
                        // combine
                        final JavaPairRDD combineRDD = mapReduce.doStage(MapReduce.Stage.COMBINE) ? SparkExecutor.executeCombine(mapRDD, newApacheConfiguration) : mapRDD;
                        // reduce
                        final JavaPairRDD reduceRDD = mapReduce.doStage(MapReduce.Stage.REDUCE) ? SparkExecutor.executeReduce(combineRDD, mapReduce, newApacheConfiguration) : combineRDD;
                        // write the map reduce output back to disk and computer result memory
                        if (null != outputRDD)
                            mapReduce.addResultToMemory(finalMemory, outputRDD.writeMemoryRDD(apacheConfiguration, mapReduce.getMemoryKey(), reduceRDD));

                    }
                }

                // unpersist the loaded graph if it will not be used again (no PersistedInputRDD)
                // if the graphRDD was loaded from Spark, but then partitioned, its a different RDD
                if ((!inputFromSpark || partitioned || filtered) && computedGraphCreated)
                    loadedGraphRDD.unpersist();
                // unpersist the computed graph if it will not be used again (no PersistedOutputRDD)
                if (!outputToSpark || this.persist.equals(GraphComputer.Persist.NOTHING))
                    computedGraphRDD.unpersist();
                // delete any file system or rdd data if persist nothing
                if (null != outputLocation && this.persist.equals(GraphComputer.Persist.NOTHING)) {
                    if (outputToHDFS)
                        fileSystemStorage.rm(outputLocation);
                    if (outputToSpark)
                        sparkContextStorage.rm(outputLocation);
                }
                // update runtime and return the newly computed graph
                finalMemory.setRuntime(System.currentTimeMillis() - startTime);
                return new DefaultComputerResult(InputOutputHelper.getOutputGraph(apacheConfiguration, this.resultGraph, this.persist), finalMemory.asImmutable());
            } finally {
                if (!apacheConfiguration.getBoolean(Constants.GREMLIN_SPARK_PERSIST_CONTEXT, false))
                    Spark.close();
            }
        }, exec);
    }

    /////////////////

    private void loadJars(final JavaSparkContext sparkContext, final Configuration hadoopConfiguration) {
        if (hadoopConfiguration.getBoolean(Constants.GREMLIN_HADOOP_JARS_IN_DISTRIBUTED_CACHE, true)) {
            final String hadoopGremlinLocalLibs = null == System.getProperty(Constants.HADOOP_GREMLIN_LIBS) ? System.getenv(Constants.HADOOP_GREMLIN_LIBS) : System.getProperty(Constants.HADOOP_GREMLIN_LIBS);
            if (null == hadoopGremlinLocalLibs)
                this.logger.warn(Constants.HADOOP_GREMLIN_LIBS + " is not set -- proceeding regardless");
            else {
                try {
                    final String[] paths = hadoopGremlinLocalLibs.split(":");
                    final FileSystem fs = FileSystem.get(hadoopConfiguration);
                    for (final String path : paths) {
                        final File file = AbstractHadoopGraphComputer.copyDirectoryIfNonExistent(fs, path);
                        if (file.exists())
                            Stream.of(file.listFiles()).filter(f -> f.getName().endsWith(Constants.DOT_JAR)).forEach(f -> sparkContext.addJar(f.getAbsolutePath()));
                        else
                            this.logger.warn(path + " does not reference a valid directory -- proceeding regardless");
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * When using a persistent context the running Context's configuration will override a passed
     * in configuration. Spark allows us to override these inherited properties via
     * SparkContext.setLocalProperty
     */
    private void updateLocalConfiguration(final JavaSparkContext sparkContext, final SparkConf sparkConfiguration) {
        /*
         * While we could enumerate over the entire SparkConfiguration and copy into the Thread
         * Local properties of the Spark Context this could cause adverse effects with future
         * versions of Spark. Since the api for setting multiple local properties at once is
         * restricted as private, we will only set those properties we know can effect SparkGraphComputer
         * Execution rather than applying the entire configuration.
         */
        final String[] validPropertyNames = {
                "spark.job.description",
                "spark.jobGroup.id",
                "spark.job.interruptOnCancel",
                "spark.scheduler.pool"
        };

        for (String propertyName : validPropertyNames) {
            if (sparkConfiguration.contains(propertyName)) {
                String propertyValue = sparkConfiguration.get(propertyName);
                this.logger.info("Setting Thread Local SparkContext Property - "
                        + propertyName + " : " + propertyValue);

                sparkContext.setLocalProperty(propertyName, sparkConfiguration.get(propertyName));
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final FileConfiguration configuration = new PropertiesConfiguration(args[0]);
        new SparkGraphComputer(HadoopGraph.open(configuration)).program(VertexProgram.createVertexProgram(HadoopGraph.open(configuration), configuration)).submit().get();
    }
}
