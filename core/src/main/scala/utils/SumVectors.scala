package org.clustering4ever.util
/**
 * @author Beck Gaël
 */
import scala.reflect.ClassTag
import scala.math.sqrt
import scala.collection.GenSeq
import scala.language.higherKinds
import org.clustering4ever.vectors.{GVector, BinaryVector, ScalarVector, MixtVector}
import scala.collection.mutable
/**
 *
 */
object VectorsAddOperationsImplicits {
	/**
	 *
	 */
	implicit def addRawScalarVectors[V <: Seq[Double]](v1: V, v2: V): V = {
		val builder = v1.genericBuilder.asInstanceOf[mutable.Builder[Double, V]]
		builder.sizeHint(v1.size)
		(0 until v1.size).foreach( i => builder += v1(i) + v2(i) )
		builder.result
	}
	/**
	 *
	 */
	implicit def addRawBinaryVectors[V <: Seq[Int]](v1: V, v2: V): V = {
		val builder = v1.genericBuilder.asInstanceOf[mutable.Builder[Int, V]]
		builder.sizeHint(v1.size)
		(0 until v1.size).foreach( i => builder += v1(i) + v2(i) )
		builder.result
	}
	/**
	 *
	 */
	implicit def addScalarVectors[V <: Seq[Double]](v1: ScalarVector[V], v2: ScalarVector[V]): ScalarVector[V] = {
		val builder = v1.vector.genericBuilder.asInstanceOf[mutable.Builder[Double, V]]
		builder.sizeHint(v1.vector.size)
		(0 until v1.vector.size).foreach( i => builder += v1.vector(i) + v2.vector(i) )
		new ScalarVector(builder.result)
	}
	/**
	 *
	 */
	implicit def addBinaryVectors[V <: Seq[Int]](v1: BinaryVector[V], v2: BinaryVector[V]): BinaryVector[V] = {
		val builder = v1.vector.genericBuilder.asInstanceOf[mutable.Builder[Int, V]]
		builder.sizeHint(v1.vector.size)
		(0 until v1.vector.size).foreach( i => builder += v1.vector(i) + v2.vector(i) )
		new BinaryVector(builder.result)
	}
	/**
	 *
	 */
	implicit def addMixtVectors[Vb <: Seq[Int], Vs <: Seq[Double]](v1: MixtVector[Vb, Vs], v2: MixtVector[Vb, Vs]): MixtVector[Vb, Vs] = {
		val binaryPart = addRawBinaryVectors(v1.binary, v2.binary)
		val scalarPart = addRawScalarVectors(v1.scalar, v2.scalar)
		new MixtVector(binaryPart, scalarPart)
	}
}
/**
 * Object which gather common operation on Vectors of any nature, aka scalar, binary, mixt
 */
object SumVectors {

	import VectorsAddOperationsImplicits._
	/**
	 * add two vector no mather their types
	 */
	def sumVectors[V](v1: V, v2: V)(implicit f: (V, V) => V): V = f(v1, v2)
	/**
	 * Reduce an Array[Array[N]] into an Array[N]
	 */
	def sumColumnMatrix[V](cluster: GenSeq[V])(implicit f: (V, V) => V): V = cluster.reduce(sumVectors(_, _))
	/**
	 * Reduce Seq of multiple vectors
	 */
	def sumAlignedVectorsMatrice[V <: Seq[Double], S[X] <: Seq[X]](a: S[V], b: S[V])(implicit f: (V, V) => V) = {	
		val range = (0 until a.size)
		val builder = a.genericBuilder.asInstanceOf[mutable.Builder[V, S[V]]]
		builder.sizeHint(a.size)
		range.foreach{ i => builder += sumVectors(a(i), b(i)) }
		builder.result
	}
	/**
	 *
	 */
	def dotProduct[V <: Seq[Double]](dot1: V, dot2: V): Double = {
		@annotation.tailrec
		def go(i: Int, sum: Double): Double = {
			val res = sum + dot1(i) * dot2(i)
			if(i < dot1.size - 1) go(i + 1, res)
			else res
		}
		go(0, 0D)
	}
	/**
	 *
	 */
	def dotProduct[V <: Seq[Double]](dot1: ScalarVector[V], dot2: ScalarVector[V]): Double = dotProduct(dot1.vector, dot2.vector)
	/**
	 *
	 */
	def euclideanNorm[V <: Seq[Double]](dot: V): Double = sqrt(dotProduct(dot, dot))
	/**
	 *
	 */
	def euclideanNorm[V <: Seq[Double]](dot: ScalarVector[V]): Double = euclideanNorm(dot.vector)
}