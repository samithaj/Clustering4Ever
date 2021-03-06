package org.clustering4ever.scala.clustering.chaining
/**
 * @author Beck Gaël
 */
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.collection.{GenSeq, mutable, immutable}
import org.clustering4ever.clustering.{ClusteringArgs, ClusteringModelCz}
import org.clustering4ever.clusterizables.Clusterizable
import org.clustering4ever.vectors.GVector
import org.clustering4ever.shapeless.{VMapping, VectorizationMapping}
import org.clustering4ever.extensibleAlgorithmNature._
import org.clustering4ever.scala.clustering.kcenters.KCenters
import shapeless.HMap
import org.clustering4ever.clustering.{ClusteringChaining, ClusteringAlgorithmCz, LocalClusteringAlgorithm}
import org.clustering4ever.vectorizations.{EmployedVectorization, DefaultWorkingVector, IthVectorization}
/**
 *
 */
class ClusteringChainingLocal[
    ID,
    O,
    V <: GVector[V],
    Cz[X, Y, Z <: GVector[Z]] <: Clusterizable[X, Y, Z, Cz],
    GS[X] <: GenSeq[X]
](
    val data: GS[Cz[ID, O, V]],
    val chainableID: Int = scala.util.Random.nextInt,
    val currentVectorization: EmployedVectorization = new DefaultWorkingVector,
    val vectorizations: HMap[VectorizationMapping] = HMap.empty[VectorizationMapping],
    val clusteringInfo: immutable.Vector[((Int, Int), EmployedVectorization, ClusteringArgs, ClusteringModelCz[_, GS])] = immutable.Vector.empty[((Int, Int), EmployedVectorization, ClusteringArgs, ClusteringModelCz[_, GS])]
) extends ClusteringChaining[ID, O, V, Cz, GS] {
    /**
     *
     */
    type Self[NV <: GVector[NV]] = ClusteringChainingLocal[ID, O, NV, Cz, GS]
    /**
     *
     */
    type AlgorithmsRestrictions[NV <: GVector[NV]] = LocalClusteringAlgorithm[NV, GS, ClusteringArgs, ClusteringModelCz[NV, GS]]
    /**
     *
     */
    protected[ClusteringChainingLocal] def fusionChainable(ccl: ClusteringChainingLocal[ID, O, V, Cz, GS]): ClusteringChainingLocal[ID, O, V, Cz, GS] = {

        val newFusionSecurity = fusionChainableSecurity + ccl.fusionChainableSecurity
        val updatedRunNumber = scala.math.max(globalClusteringRunNumber, ccl.globalClusteringRunNumber)

        new ClusteringChainingLocal(
            data.zip(ccl.data).map{ case (cz1, cz2) => cz1.addClusterIDs(cz2.clusterIDs.takeRight(ccl.fusionChainableSecurity):_*) }.asInstanceOf[GS[Cz[ID, O, V]]],
            chainableID,
            currentVectorization,
            vectorizations,
            clusteringInfo ++ ccl.clusteringInfo.takeRight(ccl.fusionChainableSecurity)
        ) {
            override protected[ClusteringChainingLocal] val fusionChainableSecurity = newFusionSecurity
            override val globalClusteringRunNumber = updatedRunNumber
        }
    }
    /**
     *
     */
    def addAnyVectorizationNature[NV <: GVector[NV]](vectorizationNature: Int, vectorizationID: Int, towardNewVector: O => NV): ClusteringChainingLocal[ID, O, V, Cz, GS] = {

        implicit val vMapping = new VectorizationMapping[Int, Map[Int, IthVectorization[O, NV]]]

        val itIsEmpty = vectorizations.get(vectorizationNature)(vMapping)
        val added = new IthVectorization(vectorizationID, towardNewVector)
        val vectorizationsUpdated = vectorizations + (
            (
                vectorizationNature,
                if(itIsEmpty.isDefined) itIsEmpty.get + ((vectorizationID, added))
                else Map(vectorizationID -> added)
            )
        )

        val updatedRunNumber = globalClusteringRunNumber
        val updatedFusionChainableSecurity = fusionChainableSecurity

        new ClusteringChainingLocal(
            data.map(_.addVectorized[NV](vectorizationID, towardNewVector)(new VMapping[Int, NV])).asInstanceOf[GS[Cz[ID, O, V]]],
            chainableID,
            currentVectorization,
            vectorizationsUpdated,
            clusteringInfo
        ) {
            override protected[ClusteringChainingLocal] val fusionChainableSecurity = updatedFusionChainableSecurity
            override val globalClusteringRunNumber = updatedRunNumber
        }

    }
    /**
     * Run one algorithm defined by user
     */
    def runAlgorithm(algorithm: LocalClusteringAlgorithm[V, GS, ClusteringArgs, ClusteringModelCz[V, GS]])(implicit ct: ClassTag[Cz[ID, O, V]]): ClusteringChainingLocal[ID, O, V, Cz, GS]
     = {
        val model = algorithm.run(data)
        val updatedRunNumber = globalClusteringRunNumber + 1
        val updatedClusteringInfo = clusteringInfo :+ (((updatedRunNumber, runNumberWhenMultiRuns), currentVectorization, algorithm.args, model))
        val clusterizedData = model.obtainClustering(data)
        new ClusteringChainingLocal(clusterizedData, chainableID, currentVectorization, vectorizations, updatedClusteringInfo) {
            override protected[ClusteringChainingLocal] val fusionChainableSecurity = 1
            override val globalClusteringRunNumber = updatedRunNumber
        }
    }
    /**
     * Run multiples algorithms defined by user
     */
    def runAlgorithms(algorithms: LocalClusteringAlgorithm[V, GS, ClusteringArgs, ClusteringModelCz[V, GS]]*)(implicit ct: ClassTag[Cz[ID, O, V]]): ClusteringChainingLocal[ID, O, V, Cz, GS] = {

        val updatedFusionChainableSecurity = fusionChainableSecurity

        algorithms.par.zipWithIndex.map{ case (alg, algIdx) =>

            val updatedRunNumber = globalClusteringRunNumber + algIdx

            (new ClusteringChainingLocal(data, chainableID, currentVectorization, vectorizations, clusteringInfo) {
                override protected[ClusteringChainingLocal] val fusionChainableSecurity = updatedFusionChainableSecurity
                override protected[ClusteringChainingLocal] val runNumberWhenMultiRuns = algIdx + 1 
                override val globalClusteringRunNumber = updatedRunNumber
            }).runAlgorithm(alg)
        }.reduce(_.fusionChainable(_))

    }
    /**
     * Run multiples algorithms defined by user as runAlgorithms but with another way that i suppose less memory greedy
     */
    def runAlgorithms2(algorithms: LocalClusteringAlgorithm[V, GS, ClusteringArgs, ClusteringModelCz[V, GS]]*)(implicit ct: ClassTag[Cz[ID, O, V]]) : ClusteringChainingLocal[ID, O, V, Cz, GS] = {

        val updatedFusionChainableSecurity = fusionChainableSecurity + algorithms.size
        val updatedGlobalClusteringRunNumber = globalClusteringRunNumber + algorithms.size

        val pAlgo = algorithms.par
        val argss = pAlgo.zipWithIndex.map{ case (algo, idx) => ((globalClusteringRunNumber + idx + 1, idx + 1), currentVectorization, algo.args) }
        val models = pAlgo.map(_.run(data))
        val clusterIDs = models.map(_.obtainClusteringIDs(data)).map(_.map(mutable.Buffer(_))).reduce(_.zip(_).map{ case (a, b) => a ++= b})
        val clusteringInfoss = argss.zip(models).map{ case (((a, b), cv, algo), model) => ((a, b), cv, algo, model) }.toVector
        val clusterizedData = data.zip(clusterIDs).map{ case (cz, ids) => cz.addClusterIDs(ids:_*) }.asInstanceOf[GS[Cz[ID, O, V]]]

        (new ClusteringChainingLocal(clusterizedData, chainableID, currentVectorization, vectorizations, clusteringInfoss) {
            override protected[ClusteringChainingLocal] val fusionChainableSecurity = updatedFusionChainableSecurity
            override protected[ClusteringChainingLocal] val runNumberWhenMultiRuns = - 1 
            override val globalClusteringRunNumber = updatedGlobalClusteringRunNumber
        })

    }
    /**
     * Update the current vector for another
     */
    def updtV[NV <: GVector[NV]](vectorizationNature: Int, vectorizationID: Int, vMapping: VMapping[Int, NV] = new VMapping[Int, NV])(implicit ct: ClassTag[Cz[ID, O, NV]]): ClusteringChainingLocal[ID, O, NV, Cz, GS] = {
        val explicitVectorization = new VectorizationMapping[Int, Map[Int, IthVectorization[O, NV]]]
        val newCurrentVectorization = vectorizations.get[Int, Map[Int, IthVectorization[O, NV]]](vectorizationNature)(explicitVectorization).get.get(vectorizationID).get
        val updatedVectorData = data.map(_.updtV[NV](vectorizationID)(vMapping)).asInstanceOf[GS[Cz[ID, O, NV]]]
        val updatedRunNumber = globalClusteringRunNumber
        val updatedFusionChainableSecurity = fusionChainableSecurity
        new ClusteringChainingLocal(updatedVectorData, chainableID, newCurrentVectorization, vectorizations, clusteringInfo) {
            override protected[ClusteringChainingLocal] val fusionChainableSecurity = updatedFusionChainableSecurity
            override val globalClusteringRunNumber = updatedRunNumber
        }
    }
    /**
     *
     */
    protected def applyDifferentVectorizationsOfSameNature[NV <: GVector[NV]](
        vectorizationNature: Int,
        vectorizationIDs: Seq[Int],
        algorithms: Seq[LocalClusteringAlgorithm[NV, GS, ClusteringArgs, ClusteringModelCz[NV, GS]]],
        otherVectorizationMapping: VMapping[Int, NV] = new VMapping[Int, NV]
    )(implicit ct: ClassTag[Cz[ID, O, NV]]): ClusteringChainingLocal[ID, O, NV, Cz, GS] = {
        vectorizationIDs.par.map( vectorizationID => updtV[NV](vectorizationNature, vectorizationID, otherVectorizationMapping)(ct).runAlgorithms(algorithms:_*) ).reduce(_.fusionChainable(_))
    }
}
/**
 *
 */
object ClusteringChainingLocal {

    def apply[ID, O, V <: GVector[V], Cz[X, Y, Z <: GVector[Z]] <: Clusterizable[X, Y, Z, Cz], GS[X] <: GenSeq[X]](data: GS[Cz[ID, O, V]]) = new ClusteringChainingLocal(data)

}