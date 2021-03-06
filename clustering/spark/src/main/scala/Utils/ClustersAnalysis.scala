package org.clustering4ever.spark.clustersanalysis
/**
 * @author Beck Gaël
 */
import scala.reflect.ClassTag
import scala.collection.{Map, mutable}
import scala.language.higherKinds
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.clustering4ever.clusterizables.Clusterizable
import org.clustering4ever.math.distances.{ClusterizableDistance, Distance, ContinuousDistance, BinaryDistance}
import org.clustering4ever.math.distances.scalar.Euclidean
import org.clustering4ever.util.ClusterBasicOperations
import org.clustering4ever.util.SumVectors
import org.clustering4ever.clustering.ClustersAnalysis
import org.clustering4ever.vectors.{GVector, ScalarVector, BinaryVector}
/**
 *
 */
trait ClustersAnalysisDistributed[
    ID,
    O,
    V <: GVector[V],
    Cz[X, Y, Z <: GVector[Z]] <: Clusterizable[X, Y, Z, Cz],
    D <: Distance[V]
] extends ClustersAnalysis[ID, O, V, Cz, D, RDD] {
    
    val metric: D

    private val neutralElement = mutable.ArrayBuffer.empty[Cz[ID, O, V]]
    def addToBuffer(buff: mutable.ArrayBuffer[Cz[ID, O, V]], elem: Cz[ID, O, V]) = buff += elem
    def aggregateBuff(buff1: mutable.ArrayBuffer[Cz[ID, O, V]], buff2: mutable.ArrayBuffer[Cz[ID, O, V]]) = buff1 ++= buff2

    lazy val datasetSize = data.count

    def groupedByClusterID(clusteringNumber: Int)(implicit ct: ClassTag[Cz[ID, O, V]]): RDD[(Int, mutable.ArrayBuffer[Cz[ID,O,V]])] = data.map( cz => (cz.clusterIDs(clusteringNumber), cz) ).aggregateByKey(neutralElement)(addToBuffer, aggregateBuff)

    // def cardinalities(clusteringNumber: Int): Map[Int, Int] = groupedByClusterID(clusteringNumber).map{ case (clusterID, aggregate) => (clusterID, aggregate.size) }.collectAsMap

    // def clustersProportions(clusteringNumber: Int): Map[Int, Double] = cardinalities(clusteringNumber).map{ case (clusterID, cardinality) => (clusterID, cardinality.toDouble / datasetSize) }

    // def centroids: Map[Int, V] = groupedByClusterID(clusteringNumber).map{ case (clusterID, aggregate) => (clusterID, ClusterBasicOperations.obtainCenter(aggregate.map(_.workingVector), metric)) }.collectAsMap

    // def cardinalities(clusteringNumber: Int): Map[Int, Long]

    // val cardinalitiesByClusteringNumber = mutable.HashMap.empty[Int, Map[Int, Long]]

    // def clustersProportions(clusteringNumber: Int): Map[Int, Double]

    // val clustersProportionsByClusteringNumber = mutable.HashMap.empty[Int, Map[Int, Double]]

    // val metric: D    

    // def centroids(clusteringNumber: Int): Map[Int, V]

    // val centroidsByClusteringNumber = mutable.HashMap.empty[Int, Map[Int, V]]

}
/**
 *
 */
// class RealClustersAnalysis[
//     ID,
//     O,
//     V <: Seq[Double],
//     Cz[X, Y, Z <: GVector[Z]] <: Clusterizable[X, Y, Z, Cz],
//     D <: ContinuousDistance[V]
// ](clusterized: RDD[Cz[ID, O, ScalarVector[V]]], metric: D)(implicit ct: ClassTag[Cz[ID, O, ScalarVector[V]]], ct2: ClassTag[V]) extends ClustersAnalysisDistributed[ID, O, ScalarVector[V], Cz, D](clusterized, metric) {

//     /**
//      * TO DO
//      * - distributions of each features
//      */

// }
/**
 *
 */
// class BinaryClustersAnalysis[
//     ID,
//     O,
//     V <: Seq[Int],
//     Cz[X, Y, Z <: GVector[Z]] <: Clusterizable[X, Y, Z, Cz],
//     D <: BinaryDistance[V]
// ](clusterized: RDD[Cz[ID, O, BinaryVector[V]]], metric: D, vectorHeader: Option[mutable.ArrayBuffer[String]] = None, eachCategoryRange: Option[mutable.ArrayBuffer[Int]] = None)(implicit ct: ClassTag[Cz[ID, O, BinaryVector[V]]], ct2: ClassTag[V]) extends ClustersAnalysisDistributed[ID, O, BinaryVector[V], Cz, D](clusterized, metric) {

//     private val originalVector = 0

//     import org.clustering4ever.util.VectorsAddOperationsImplicits._

//     if(vectorHeader.isDefined) require(clusterized.first.workingVector.vector.size == vectorHeader.size)

//     lazy val occurencesPerFeature: V = clusterized.map(_.workingVector.vector).reduce(SumVectors.sumVectors(_, _))

//     lazy val frequencyPerFeature: Seq[Double] = occurencesPerFeature.map(_.toDouble / datasetSize)

//     def occurencesPerFeaturePerCluster(clusteringNumber: Int) = groupedByClusterID(clusteringNumber).map{ case (clusterID, aggregate) => (clusterID, aggregate.map(_.workingVector.vector).reduce(SumVectors.sumVectors(_, _))) }.collectAsMap

//     def frequencyPerFeaturePerCluster(clusteringNumber: Int) = occurencesPerFeaturePerCluster(clusteringNumber).map{ case (clusterID, occurences) => (clusterID, occurences.map(_.toDouble / cardinalities(clusterID))) }
// }