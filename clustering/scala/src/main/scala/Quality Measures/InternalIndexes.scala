package org.clustering4ever.scala.indices
/**
 * @author Beck Gaël
 */
import scala.language.higherKinds
import scala.math.{max, min}
import scala.collection.{GenSeq, mutable}
import scala.language.higherKinds
import org.clustering4ever.math.distances.scalar.Euclidean
import org.clustering4ever.math.distances.binary.Hamming
import org.clustering4ever.math.distances.{Distance, ContinuousDistance, BinaryDistance}
import org.clustering4ever.clustering.ClusteringCommons
import org.clustering4ever.util.ClusterBasicOperations
/**
 *
 */
class InternalIndices[V, D <: Distance[V]](clusterized: GenSeq[(Int, V)], metric: D, clustersIDsOp: Option[mutable.ArraySeq[Int]] = None) extends InternalIndicesCommons[V, D] {
  /**
   *
   */
  lazy val clustersIDs = if(clustersIDsOp.isDefined) clustersIDsOp.get else mutable.ArraySeq(clusterized.map(_._1).distinct.seq:_*).sorted
  /**
   *
   */
  lazy val daviesBouldin: Double = {

    if(clustersIDs.size == 1) {
      println(" One Cluster found")
      0D
    }
    else {
      val clusters = clusterized.groupBy(_._1).map{ case (k, v) => (k, v.map(_._2)) }
      val centers = clusters.map{ case (k, cluster) => (k, ClusterBasicOperations.obtainCenter(cluster, metric)) }.toArray
      val scatters = clusters.zipWithIndex.map{ case ((k, cluster), idCLust) => (k, scatter(cluster, centers(idCLust)._2, metric)) }
      val clustersWithCenterandScatters = (centers.map{ case (id, ar) => (id, (Some(ar), None)) } ++ scatters.map{ case (id, v) => (id, (None, Some(v))) })
        .par
        .groupBy(_._1)
        .map{ case (id, aggregate) => 
          val agg = aggregate.map(_._2)
          val a = agg.head
          val b = agg.last
          if(a._1.isDefined) (id, (b._2.get, a._1.get)) else (id, (a._2.get, b._1.get))
        }
      val cart = for( i <- clustersWithCenterandScatters; j <- clustersWithCenterandScatters if i._1 != j._1 ) yield (i, j)
      val rijList = cart.map{ case ((idClust1, (centroid1, scatter1)), (idClust2, (centroid2, scatter2))) => (idClust1, good(centroid1, centroid2, scatter1, scatter2, metric)) }
      val di = rijList.groupBy(_._1).map{ case (_, goods) => goods.map(_._2).reduce(max(_,_)) }
      val numCluster = clustersIDs.size
      val daviesBouldinIndex = di.sum / numCluster
      daviesBouldinIndex
    }

  }
  /**
   *
   */
  lazy val ballHall: Double = {
    
    val clusters = clusterized.groupBy(_._1).map{ case (clusterID, aggregate) => (clusterID, aggregate.map(_._2)) }
    
    val prototypes = clusters.map{ case (clusterID, cluster) => (clusterID, ClusterBasicOperations.obtainCenter(cluster, metric)) }
    
    clusters.map{ case (clusterID, aggregate) => aggregate.map( v => metric.d(v, prototypes(clusterID)) ).sum / aggregate.size }.sum / clusters.size

  }
  /**
   * Silhouette Index
   * Complexity : O(n<sup>2</sup>)
   */
  lazy val silhouette: Double = {  
    /*
     * Compute the  within-cluster mean distance a(i) for all the point in cluster
     * Param: cluster: RDD[Seq]
     * Return index of point and the corresponding a(i) Array[(Int, Double)]
     */
    def aiList(cluster: Seq[(Int, V)]): Map[Int, Double] = {
      val pointPairs = for( i <- cluster; j <- cluster if i._1 != j._1 ) yield (i, j)
      val allPointsDistances = pointPairs.map( pp => ((pp._1._1, pp._2._1), metric.d(pp._1._2, pp._2._2)) )
      val totalDistanceList = allPointsDistances.map(v => (v._1._1, v._2)).groupBy(_._1).map{ case (k, v) => (k, v.map(_._2).sum) }
      val count = totalDistanceList.size
      val aiList = totalDistanceList.map{ case (k, v) => (k, (v / (count - 1))) }
      aiList
    }
    /*
     * The mean of the silhouette widths for a given cluster
     * @param : label: Int - the cluster label that we want to compute
     * @return :  Double
     */
    def sk(testedLabel: Int) = {
      val uniqclusterized = clusterized.zipWithIndex
      val (target, others) = uniqclusterized.partition{ case ((clusterID, _), _) => clusterID == testedLabel }
      val cart = for( i <- target; j <- others ) yield (i, j)
      val allDistances = cart.map{ case (((_, vector1), id1), ((clusterID2, vector2), _)) => ((id1, clusterID2), metric.d(vector1, vector2)) }.groupBy(_._1).map{ case (k,v)=> (k, v.map(_._2).sum) }
      val numPoints = others.map( v => (v._1._1, 1) ).groupBy(_._1).map{ case (k, v)=> (k, v.map(_._2).sum) }
      val deltas = allDistances.map( v => (v._1._1, v._2 / numPoints.getOrElse(v._1._2, 1)) )
      val bi = deltas.groupBy(_._1).map{ case (k, v) => (k, v.map(_._2).reduce(min(_, _))) }
      val ai = aiList(target.map(v => (v._2, v._1._2)).seq).par
      val si = (ai.map{ case (id, d) => (id, (Some(d), None)) } ++ bi.map{ case (id, d) => (id, (None, Some(d))) })
        .groupBy(_._1)
        .map{ case (id, aggregate) => 
          val agg = aggregate.map(_._2)
          val a = agg.head
          val b = agg.last
          if(a._1.isDefined) (id, (b._2.get, a._1.get)) else (id, (a._2.get, b._1.get))
        }
        .map( x => (x._2._1 - x._2._2) / max(x._2._2, x._2._1) )
      val sk = si.sum / si.size
      sk
    }
    clustersIDs.map(sk).sum / clustersIDs.size
  }

}
/**
 *
 */
object InternalIndices extends ClusteringCommons {
  /**
   * Davies bouldin index
   * Complexity O(n.c<sup>2</sup>) with:
   *   * n number of clusterized points
   *   * c number of clusters
   */
  def daviesBouldin[V, D <: Distance[V]](clusterized: GenSeq[(ClusterID, V)], metric: D, clusterLabels: Option[Seq[ClusterID]] = None): Double = {
    val internalIndices = new InternalIndices(clusterized, metric)
    internalIndices.daviesBouldin
  }
  /**
   *
   */
  def silhouette[V, D <: Distance[V]](clusterized: GenSeq[(ClusterID, V)], metric: D, clusterLabels: Option[Seq[ClusterID]] = None): Double = {
    val internalIndices = new InternalIndices(clusterized, metric)
    internalIndices.silhouette
  }
  /**
   *
   */
  def ballHall[V, D <: Distance[V]](clusterized: GenSeq[(ClusterID, V)], metric: D) = {
    (new InternalIndices(clusterized, metric)).ballHall
  }

}