package org.clustering4ever.scala.clustering.kprototypes
/**
 * @author Beck Gaël
 */
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.collection.{mutable, GenSeq}
import scala.util.Random
import org.clustering4ever.math.distances.MixtDistance
import org.clustering4ever.math.distances.Distance
import org.clustering4ever.math.distances.mixt.HammingAndEuclidean
import org.clustering4ever.scala.clustering.kcenters.{KCentersModel, KCenters}
import org.clustering4ever.scala.clusterizables.{Clusterizable, EasyClusterizable}
import org.clustering4ever.util.ScalaImplicits._
import org.clustering4ever.scala.vectors.{GVector, MixtVector}
/**
 * The famous K-Prototypes using a user-defined dissmilarity measure.
 * @param data :
 * @param k : number of clusters
 * @param epsilon : minimal threshold under which we consider a centroid has converged
 * @param maxIterations : maximal number of iteration
 * @param metric : a defined dissimilarity measure
 */
object KPrototypes {
	/**
	 * Run the K-Prototypes with any mixt distance
	 */
	def run[
		ID,
		O,
		Vb <: Seq[Int],
		Vs <: Seq[Double],
		Cz[X, Y, Z <: GVector] <: Clusterizable[X, Y, Z, Cz],
		D <: MixtDistance[Vb, Vs]
	](
		data: GenSeq[Cz[ID, O, MixtVector[Vb, Vs]]],
		k: Int,
		metric: D,
		maxIterations: Int,
		epsilon: Double,
		initializedCenters: mutable.HashMap[Int, MixtVector[Vb, Vs]] = mutable.HashMap.empty[Int, MixtVector[Vb, Vs]]
	)(implicit ct: ClassTag[Cz[ID, O, MixtVector[Vb, Vs]]]): KCentersModel[MixtVector[Vb, Vs], D] = {
		val kPrototypesModel = KCenters.run(data, k, metric, epsilon, maxIterations, initializedCenters)
		kPrototypesModel
	}
}