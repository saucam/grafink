/*
 * Copyright 2020 AstroLab Software
 * Author: Yash Datta
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.astrolabsoftware.grafink.processor

import org.apache.spark.HashPartitioner
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource
import org.apache.tinkerpop.gremlin.structure.Vertex
import org.janusgraph.core.JanusGraph
import org.janusgraph.graphdb.database.StandardJanusGraph
import zio._
import zio.logging.{log, Logging}

import com.astrolabsoftware.grafink.JanusGraphEnv.withGraph
import com.astrolabsoftware.grafink.Job.{SparkEnv, VertexData}
import com.astrolabsoftware.grafink.models.JanusGraphConfig
import com.astrolabsoftware.grafink.models.config.Config
import com.astrolabsoftware.grafink.processor.EdgeProcessor.{EdgeStats, MakeEdge}
import com.astrolabsoftware.grafink.processor.edgerules.VertexClassifierRule

/**
 * Interface that loads the edges data into JanusGraph
 */
object EdgeProcessor {

  // TODO Support proper data type for edge properties, and support multiple properties
  case class MakeEdge(src: Long, dst: Long, propVal: Long)
  case class EdgeStats(count: Int, partitions: Int)

  type EdgeProcessorService = Has[EdgeProcessor.Service]

  trait Service {
    def process(vertexData: VertexData, rules: List[VertexClassifierRule]): ZIO[Logging, Throwable, Unit]
    def loadEdges(edgesRDD: Dataset[MakeEdge], label: String): ZIO[Logging, Throwable, Unit]
  }

  /**
   * Get the EdgeProcessLive instance
   */
  val live: URLayer[Has[JanusGraphConfig] with Logging, EdgeProcessorService] =
    ZLayer.fromEffect(
      for {
        janusGraphConfig <- Config.janusGraphConfig
      } yield EdgeProcessorLive(janusGraphConfig)
    )

  /**
   * Given a collection of rules, adds a set of edges per rule to the graph
   * @param vertexData
   * @param rules
   * @return
   */
  def process(
    vertexData: VertexData,
    rules: List[VertexClassifierRule]
  ): ZIO[EdgeProcessorService with Logging, Throwable, Unit] =
    ZIO.accessM(_.get.process(vertexData, rules))
}

/**
 * Loads edge data into JanusGraph
 * @param config
 */
final case class EdgeProcessorLive(config: JanusGraphConfig) extends EdgeProcessor.Service {

  override def process(vertexData: VertexData, rules: List[VertexClassifierRule]): ZIO[Logging, Throwable, Unit] =
    for {
      _ <- ZIO.collectAll(rules.map { rule =>
        for {
          _ <- log.info(s"Adding edges using rule ${rule.name}")
          _ <- loadEdges(rule.classify(vertexData.loaded, vertexData.current), rule.getEdgeLabel)
        } yield ()
      })
    } yield ()

  /* def job(
    config: JanusGraphConfig,
    graph: JanusGraph,
    label: String,
    partition: Iterator[MakeEdge]
  ): ZIO[Any, Throwable, Unit] = {

    val batchSize = config.edgeLoader.batchSize
    // val g         = graph.traversal()
    val idManager = graph.asInstanceOf[StandardJanusGraph].getIDManager
    val kgroup    = partition.grouped(batchSize)
    val l = kgroup.map { group =>
    val tx         = graph.newTransaction()
    val g = tx.traversal()
    val vCache = ZRef.make(Map.empty[Long, Vertex])
      val getFromCacheOrGraph: (GraphTraversalSource, Long) => ZIO[Any, Throwable, Vertex] = (g, id) =>
        for {
          cacheRef <- vCache
          cache    <- cacheRef.get
          vertex <- ZIO
            .fromOption(cache.get(id))
            .orElse(
              for {
                v <- ZIO.effect(g.V(java.lang.Long.valueOf(id)).next())
                _ <- cacheRef.update(_.updated(id, v))
              } yield v
            )
        } yield vertex
    for {
        _ <- ZIO.collectAll_(
          group.map {
            r =>
              // TODO: Optimize
              for {
                srcVertex <- getFromCacheOrGraph(g, idManager.toVertexId(r.src))
                dstVertex <- ZIO.effect(g.V(java.lang.Long.valueOf(idManager.toVertexId(r.dst))).next())
                // dstVertex <- getFromCacheOrGraph(g, idManager.toVertexId(r.dst))
                _ <- ZIO.effect(srcVertex.addEdge(label, dstVertex).property("value", r.propVal))
                // Add reverse edge as well
                _ <- ZIO.effect(dstVertex.addEdge(label, srcVertex).property("value", r.propVal))
              } yield ()
          }
        )
        // Clear cache
        // cacheRef <- vCache
        // _ <- cacheRef.update(_ => Map.empty[Long, Vertex])
        // Commit
        _ <- ZIO.effect(tx.commit)
        _ <- ZIO.effect(g.close())
      } yield ()
  }
    for {
      _ <- ZIO.collectAll_(l.toIterable)

    } yield ()
  }

   */

  def job(
           config: JanusGraphConfig,
           graph: JanusGraph,
           label: String,
           partition: Iterator[MakeEdge]
         ): ZIO[Any, Throwable, Unit] = {

    val batchSize = config.edgeLoader.batchSize
      ZIO.effect(graph.traversal()).bracket(
        g => ZIO.effect(g.close()).fold(_ => log.error("Error"), _ => log.info("succeed")),
        g => ZIO.effect {

          val idManager = graph.asInstanceOf[StandardJanusGraph].getIDManager

          var i = 0
          while (partition.hasNext) {
            val edge = partition.next
            val srcVertex1 = g.V(java.lang.Long.valueOf(idManager.toVertexId(edge.src)))
            val dstVertex1 = g.V(java.lang.Long.valueOf(idManager.toVertexId(edge.dst)))
            // srcVertex.addEdge(label, dstVertex).property("value", edge.propVal)
            srcVertex1.addE(label).to(dstVertex1).property("value", edge.propVal).iterate

            val srcVertex2 = g.V(java.lang.Long.valueOf(idManager.toVertexId(edge.src)))
            val dstVertex2 = g.V(java.lang.Long.valueOf(idManager.toVertexId(edge.dst)))
            dstVertex2.addE(label).to(srcVertex2).property("value", edge.propVal).iterate
            // dstVertex.addEdge(label, srcVertex).property("value", edge.propVal)
            i += 1
            if (i == batchSize) {
              g.tx.commit
              i = 0
            }
          }
          g.tx.commit
        })
  }


  def getParallelism(numberOfEdgesToLoad: Int): EdgeStats = {
    val numPartitions = if (numberOfEdgesToLoad < config.edgeLoader.taskSize) {
      config.edgeLoader.parallelism
    } else {
      scala.math.max((numberOfEdgesToLoad / config.edgeLoader.taskSize) + 1, config.edgeLoader.parallelism)
    }
    EdgeStats(count = numberOfEdgesToLoad, numPartitions)
  }

  override def loadEdges(edges: Dataset[MakeEdge], label: String): ZIO[Logging, Throwable, Unit] = {

    val c       = config
    val jobFunc = job _

    // Add edges to all rows within a partition
    def loadFunc: (Iterator[MakeEdge]) => Unit = (partition: Iterator[MakeEdge]) => {
      val executorJob = withGraph(c, graph => jobFunc(c, graph, label, partition))
      zio.Runtime.default.unsafeRun(executorJob)
    }

    val load = loadFunc

    for {
      numberOfEdgesToLoad <- ZIO.effect(edges.count.toInt)
      stats = getParallelism(numberOfEdgesToLoad)
      _ <- ZIO
        .effect(
          // We repartition and sort within the partitions so that we load all edges from the same srcvertex together
          edges.sparkSession.sparkContext
            .runJob(edges.rdd.keyBy(_.src).repartitionAndSortWithinPartitions(new HashPartitioner(stats.partitions)).values, load)
        )
        .fold(
          f => log.error(s"Error while loading edges $f"),
          _ => log.info(s"Successfully loaded ${stats.count} edges to graph backed by ${config.storage.tableName}")
        )
    } yield ()
  }
}
