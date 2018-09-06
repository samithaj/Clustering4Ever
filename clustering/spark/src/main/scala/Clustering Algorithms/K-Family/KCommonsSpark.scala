package clustering4ever.spark.clustering

import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.util.Pair
import scala.collection.JavaConverters._
import scala.math.pow
import scala.collection.{immutable, mutable, parallel}
import scala.util.Random
import scala.reflect.ClassTag
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import clustering4ever.math.distances.Distance
import clustering4ever.stats.Stats
import clustering4ever.scala.clusterizables.{ClusterizableExt, Clusterizable}
import clustering4ever.scala.clustering.KCommons
import clustering4ever.clustering.CommonRDDPredictClusteringModel
import clustering4ever.util.SumVectors
import clustering4ever.scala.measurableclass.BinaryScalarVector

abstract class KCommonsSpark[
	ID: Numeric,
	V : ClassTag,
	D <: Distance[V],
	Cz <: Clusterizable[ID, V]
	](
	data: RDD[Cz],
	metric: D,
	k: Int,
	initializedCenters: mutable.HashMap[Int, V] = mutable.HashMap.empty[Int, V],
	persistanceLVL: StorageLevel = StorageLevel.MEMORY_ONLY
) extends KCommons[ID, V, D](metric)
{
	val vectorizedDataset: RDD[V] = data.map(_.vector).persist(persistanceLVL)
	// To upgrade
	val centers: mutable.HashMap[Int, V] = if( initializedCenters.isEmpty ) kmppInitializationRDD(vectorizedDataset, k) else initializedCenters
	val kCentersBeforeUpdate: mutable.HashMap[Int, V] = centers.clone
	val clustersCardinality: mutable.HashMap[Int, Long] = centers.map{ case (clusterID, _) => (clusterID, 0L) }
	
	private def updateCentersAndCardinalities(centersInfo: Iterable[(Int, V, Long)]) =
	{
		centersInfo.foreach{ case (clusterID, center, cardinality) =>
		{
			kCentersBeforeUpdate(clusterID) = center.asInstanceOf[V]
			clustersCardinality(clusterID) = cardinality
		}}
	}


	protected def checkIfConvergenceAndUpdateCenters(centersInfo: Iterable[(Int, V, Long)], epsilon: Double) =
	{
		updateCentersAndCardinalities(centersInfo)
		val allModHaveConverged = areCentersMovingEnough(kCentersBeforeUpdate, centers, epsilon)
		kCentersBeforeUpdate.foreach{ case (clusterID, mode) => centers(clusterID) = mode }	
		allModHaveConverged
	}

	/**
	 * To upgrade
	 * Kmeans++ initialization
	 * <h2>References</h2>
	 * <ol>
	 * <li> Tapas Kanungo, David M. Mount, Nathan S. Netanyahu, Christine D. Piatko, Ruth Silverman, and Angela Y. Wu. An Efficient k-Means Clustering Algorithm: Analysis and Implementation. IEEE TRANS. PAMI, 2002.</li>
	 * <li> D. Arthur and S. Vassilvitskii. "K-means++: the advantages of careful seeding". ACM-SIAM symposium on Discrete algorithms, 1027-1035, 2007.</li>
	 * <li> Anna D. Peterson, Arka P. Ghosh and Ranjan Maitra. A systematic evaluation of different methods for initializing the K-means clustering algorithm. 2010.</li>
	 * </ol>
	 */
	protected def kmppInitializationRDD(vectorizedDataset: RDD[V], k: Int): mutable.HashMap[Int, V] =
	{
		def obtainNearestCenter(v: V, centers: mutable.ArrayBuffer[V]): V = centers.minBy(metric.d(_, v))
		
		val centersBuff = mutable.ArrayBuffer(vectorizedDataset.first)

		(1 until k).foreach( i => centersBuff += Stats.obtainCenterFollowingWeightedDistribution[V](vectorizedDataset.map( v => (v, pow(metric.d(v, obtainNearestCenter(v, centersBuff)), 2)) ).collect.toBuffer) )

		val centers = mutable.HashMap(centersBuff.zipWithIndex.map{ case (center, clusterID) => (clusterID, center) }:_*)
		centers
	}
}

abstract class KCommonsSparkVectors[
	ID: Numeric,
	N: Numeric,
	V <: Seq[N] : ClassTag,
	Cz <: Clusterizable[ID, V],
	D <: Distance[V]
	](
	data: RDD[Cz],
	metric: D,
	k: Int,
	initializedCenters: mutable.HashMap[Int, V] = mutable.HashMap.empty[Int, V],
	persistanceLVL: StorageLevel = StorageLevel.MEMORY_ONLY
) extends KCommonsSpark[ID, V, D, Cz](data, metric, k, initializedCenters, persistanceLVL)
{
	val dim = vectorizedDataset.first.size

	protected def obtainvalCentersInfo = vectorizedDataset.map( v => (obtainNearestCenterID(v, centers), (1L, v)) ).reduceByKeyLocally{ case ((sum1, v1), (sum2, v2)) => (sum1 + sum2, SumVectors.sumVectors[N, V](v1, v2)) }

	/**
	 * To upgrade
	 */
	def initializationCentersR(vectorizedDataset: RDD[mutable.ArrayBuffer[Double]]) =
	{
		val vectorRange = (0 until dim).toBuffer

		val (minv, maxv) = vectorizedDataset.map{ v =>
			val vector = v.toBuffer
			(vector, vector)
		}.reduce( (minMaxa, minMaxb) => vectorRange.map( i => Stats.obtainIthMinMax(i, minMaxa, minMaxb) ).unzip )

		val ranges = mutable.ArrayBuffer(minv.zip(maxv):_*).map{ case (min, max) => (max - min, min) }
		val centers = mutable.HashMap((0 until k).map( clusterID => (clusterID, ranges.map{ case (range, min) => Random.nextDouble * range + min }) ):_*)
		centers
	}
}

abstract class KCommonsSparkMixt[
	ID: Numeric,
	Vb <: Seq[Int],
	Vs <: Seq[Double],
	V <: BinaryScalarVector[Vb, Vs] : ClassTag,
	Cz <: Clusterizable[ID, V],
	D <: Distance[V]
	](
	data: RDD[Cz],
	metric: D,
	k: Int,
	initializedCenters: mutable.HashMap[Int, V] = mutable.HashMap.empty[Int, V],
	persistanceLVL: StorageLevel = StorageLevel.MEMORY_ONLY
) extends KCommonsSpark[ID, V, D, Cz](data, metric, k, initializedCenters, persistanceLVL)
{
	protected val dimBinary = vectorizedDataset.first.binary.size
	protected val dimScalar = vectorizedDataset.first.scalar.size

	protected def obtainvalCentersInfo =
	{
		vectorizedDataset.map( v => (obtainNearestCenterID(v, centers), (1L, v)) ).reduceByKeyLocally{ case ((sum1, v1), (sum2, v2)) =>
			{
				(
					sum1 + sum2,
					{
						val binaryVector = SumVectors.sumVectors[Int, Vb](v1.binary, v2.binary)
						val scalarVector = SumVectors.sumVectors[Double, Vs](v1.scalar, v2.scalar)
						(new BinaryScalarVector[Vb, Vs](binaryVector, scalarVector)).asInstanceOf[V]
					}
				)
			}
		}
	}
	// def naiveInitializationCenters() =
	// {
	// 	val vectorRange = (0 until dimScalar).toBuffer
	// 	val kRange = (0 until k)
	// 	val binaryModes = kRange.map( clusterID => (clusterID, Seq.fill(dimBinary)(Random.nextInt(2)).asInstanceOf[Vb]) )

	// 	val (minv, maxv) = data.map( v =>
	// 	{
	// 		val vector = v.scalar.toBuffer
	// 		(vector, vector)
	// 	}).reduce( (minMaxa, minMaxb) => vectorRange.map( i => Stats.obtainIthMinMax(i, minMaxa, minMaxb) ).unzip )

	// 	val ranges = minv.zip(maxv).map{ case (min, max) => (max - min, min) }.toSeq
	// 	val scalarCentroids = kRange.map( clusterID => (clusterID, ranges.map{ case (range, min) => Random.nextDouble * range + min }.asInstanceOf[Vs]) )

	// 	mutable.HashMap(binaryModes.zip(scalarCentroids).map{ case ((clusterID, binaryVector), (_, scalarVector)) => (clusterID, (new BinaryScalarVector[Vb, Vs](binaryVector, scalarVector)).asInstanceOf[V]) }:_*)
	// }
}

abstract class KCommonsModelSpark[
	ID: Numeric,
	V,
	D <: Distance[V],
	Cz <: ClusterizableExt[ID, V] : ClassTag
	](
	val centers: mutable.HashMap[Int, V],
	val metric: D
) extends CommonRDDPredictClusteringModel[V, D]
{
	/**
	 * Time complexity O(n<sub>data</sub>.c) with c the number of clusters
	 * @return the input Seq with labels obtain via centerPredict method
	 **/
	def centerPredict(data: RDD[Cz])(implicit i: DummyImplicit): RDD[Cz] = data.map( rc => rc.setClusterID(centerPredict(rc.vector)) )
	/**
	 * Time complexity O(n<sub>data</sub>.n<sub>trainDS</sub>)
	 * @return the input Seq with labels obtain via knnPredict method
	 */
	def knnPredict(data: RDD[Cz], k: Int, trainDS: Seq[(ClusterID, V)])(implicit i: DummyImplicit): RDD[Cz] = data.map( rc => rc.setClusterID(knnPredict(rc.vector, k, trainDS)) )
	/**
	 * Time complexity O(n<sub>data</sub>.n<sub>trainDS</sub>)
	 * @return the input Seq with labels obtain via knnPredict method
	 */
	def knnPredict(data: RDD[Cz], k: Int, trainDS: Seq[Cz]): RDD[Cz] = knnPredict(data, k, trainDS.map( rc => (rc.clusterID, rc.vector) ))
}