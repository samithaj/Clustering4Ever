package org.clustering4ever.spark.clustering.clusterwise
/**
 * @æuthor Beck Gaël
 */
import scala.language.higherKinds
import org.apache.spark.rdd.RDD
import breeze.linalg.{DenseMatrix, DenseVector}
import scala.collection.{immutable, GenSeq, mutable}
import scala.collection.parallel.ParSeq
import org.clustering4ever.math.distances.RawContinuousDistance
import org.clustering4ever.math.distances.scalar.RawEuclidean
import org.clustering4ever.clustering.ClusteringModel
/**
 *
 */
class ClusterwiseModel[V <: Seq[Double]](
	val xyTrain: GenSeq[(Int, (V, V, Int))],
	interceptXYcoefPredByClass: immutable.Map[Int, (Array[Double], breeze.linalg.DenseMatrix[Double], immutable.IndexedSeq[(Int, Array[Double])])],
	standardizationParameters: Option[(mutable.ArrayBuffer[Double], mutable.ArrayBuffer[Double], mutable.ArrayBuffer[Double], mutable.ArrayBuffer[Double])] = None,
	metric: RawContinuousDistance[V] = new RawEuclidean[V]
) extends ClusteringModel {
	type Xvector = V
	type Yvector = V
	type IDXtest = Seq[(Long, Xvector)]
	type IDXYtest = ParSeq[(Int, (Xvector, Yvector))]

	private[this] def knn(v: V, neighbors: Seq[(V, Int)], k:Int) = neighbors.sortBy{ case (altVectors, clusterID) => metric.d(v, altVectors) }.take(k)

	private[this] def obtainNearestClass(x: V, k: Int, g: Int, withY: Boolean) = {
		val neighbours = if( withY ) xyTrain.map{ case (_, (x2, y2, label2)) => ((x2 ++ y2).asInstanceOf[V], label2) } else xyTrain.map{ case (_, (x2, _, label2)) => (x2, label2) }
		val majVote = knn(x, neighbours.seq, k)
		val cptVote = Array.fill(g)(0)
		majVote.foreach{ case (_, clusterID) => cptVote(clusterID % g) += 1 }
		val classElem = cptVote.indexOf(cptVote.max)
		classElem
	}

	private[this] def knnMajorityVote(xyTest: Iterator[(Long, Xvector)], k: Int, g: Int): Iterator[(Long, Int, V)] = xyTest.map{ case (id, x) => (id, obtainNearestClass(x, k, g, false), x) }
	
	private[this] def knnMajorityVoteWithY(xyTest: IDXYtest, k: Int, g: Int): ParSeq[(Int, Int, V)] = xyTest.map{ case (id, (x, y)) => (id, obtainNearestClass((x ++ y).asInstanceOf[V], k, g, true), x) }

	private[this] def knnMajorityVote2(xyTest: ParSeq[(Int, (Xvector, Yvector))], k: Int, g: Int): ParSeq[(Int, Int, V)] = xyTest.map{ case (id, (x, y)) => (id, obtainNearestClass(x, k, g, false), x) }

	private[this] def knnMajorityVoteWithY2(xyTest: Iterator[(Int, (Xvector, Yvector))], k: Int, g: Int): Iterator[(Int, Int, V)] = xyTest.map{ case (id, (x, y)) => (id, obtainNearestClass((x ++ y).asInstanceOf[V], k, g, true), x) }

	def predictClusterViaKNNLocal(xyTest: ParSeq[(Int, (Xvector, Yvector))], k: Int, g: Int, withY: Boolean = true) = {
		val labelisedData = if(withY) knnMajorityVoteWithY(xyTest, k, g) else knnMajorityVote2(xyTest, k, g)
		val yPred = labelisedData.map{ case(id, clusterID, x) =>
			val (intercept, xyCoef, _) = interceptXYcoefPredByClass(clusterID)
			val prediction = (DenseVector(intercept).t + DenseVector(x.toArray).t * xyCoef).t
			(id, (clusterID, prediction))
		}
		yPred
	}

	def cwPredictionKNNdistributed(xyTest: RDD[(Int, (Xvector, Yvector))], k: Int, g: Int) = {
		val labelisedData = xyTest.mapPartitions( it => knnMajorityVoteWithY2(it, k, g) )
		val yPred = labelisedData.map{ case (id, clusterID, x) =>
			val (intercept, xyCoef, _) = interceptXYcoefPredByClass(clusterID)
			(id, (clusterID, (DenseVector(intercept).t + DenseVector(x.toArray).t * xyCoef).t))
		}

		yPred
	}

	def predictKNN(toPredict: RDD[(Long, Xvector)], k: Int, g: Int) = {
		require(standardizationParameters.isDefined, "You have to pass standardization parameters to clusterwise model")
		val (_, meanY, _, sdY) = standardizationParameters.get
		val labelisedData = toPredict.mapPartitions( it => knnMajorityVote(it, k, g) )
		val yPred = labelisedData.map{ case (id, clusterID, x) =>
			val (intercept, xyCoef, _) = interceptXYcoefPredByClass(clusterID)
			val standardizedOrNotPrediction = ((DenseVector(intercept).t + DenseVector(x.toArray).t * xyCoef).t).toArray
			(
				id,
				(
					clusterID,
					if(standardizationParameters.isDefined) standardizedOrNotPrediction.zipWithIndex.map{ case (y, idx) => y * sdY(idx) + meanY(idx) } else standardizedOrNotPrediction
				)
			)
		}
		yPred
	}

	def standardizeData(toStandardize: RDD[(Long, Xvector)]) = {
		require(standardizationParameters.isDefined, "You have to pass standardization parameters to clusterwise model")
		val (meanX, _, sdX, _) = standardizationParameters.get
		toStandardize.map{ case (id, x) => (id, x.zip(meanX).zip(sdX).map{ case ((value, mean), sd) => (value - mean) / sd }) }
	}

}