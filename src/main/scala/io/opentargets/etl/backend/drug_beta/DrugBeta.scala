package io.opentargets.etl.backend.drug_beta

import com.typesafe.scalalogging.LazyLogging
import io.opentargets.etl.backend.ETLSessionContext
import io.opentargets.etl.backend.drug_beta.DrugCommon._
import io.opentargets.etl.backend.spark.Helpers
import io.opentargets.etl.backend.spark.Helpers.IOResourceConfig
import org.apache.spark.sql.functions.{array_contains, col, map_keys}
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
  * This step will eventually replace the existing Drug step.
  *
  * It incorporates processing which was previously done in the `data-pipeline` project and consolidates all the logic in
  * this class.
  */
object DrugBeta extends Serializable with LazyLogging {

  def apply()(implicit context: ETLSessionContext): Unit = {
    implicit val ss: SparkSession = context.sparkSession

    import ss.implicits._

    val common = context.configuration.common

    logger.info("Loading raw inputs for Drug beta step.")
    val mappedInputs = Map(
      "indication" -> IOResourceConfig(common.inputs.drugChemblIndication.format,
                                       common.inputs.drugChemblIndication.path),
      "mechanism" -> IOResourceConfig(common.inputs.drugChemblMechanism.format,
                                      common.inputs.drugChemblMechanism.path),
      "molecule" -> IOResourceConfig(common.inputs.drugChemblMolecule.format,
                                     common.inputs.drugChemblMolecule.path),
      "target" -> IOResourceConfig(common.inputs.drugChemblTarget.format,
                                   common.inputs.drugChemblTarget.path),
      "drugbankChemblMap" -> IOResourceConfig(common.inputs.drugDrugbankToChembl.format,
                                              common.inputs.drugDrugbankToChembl.path,
                                              Some("\\t"),
                                              Some(true)),
      "efo" -> IOResourceConfig(common.inputs.disease.format, common.inputs.disease.path),
      "gene" -> IOResourceConfig(common.inputs.target.format, common.inputs.target.path),
      "evidence" -> IOResourceConfig(common.inputs.evidence.format, common.inputs.evidence.path)
    )

    val inputDataFrames = Helpers.readFrom(mappedInputs)

    // raw input dataframes
    lazy val moleculeDf: DataFrame = inputDataFrames("molecule")
    lazy val mechanismDf: DataFrame = inputDataFrames("mechanism")
    lazy val indicationDf: DataFrame = inputDataFrames("indication")
    lazy val targetDf: DataFrame = inputDataFrames("target")
    lazy val geneDf: DataFrame = inputDataFrames("gene")
    lazy val drugbank2ChemblMap: DataFrame = inputDataFrames("drugbankChemblMap")
      .withColumnRenamed("From src:'1'", "id")
      .withColumnRenamed("To src:'2'", "drugbank_id")
    lazy val efoDf: DataFrame = inputDataFrames("efo")
    lazy val evidenceDf: DataFrame = inputDataFrames("evidence")

    // processed dataframes
    logger.info("Raw inputs for Drug beta loaded.")
    logger.info("Processing Drug beta transformations.")
    val mechanismOfActionProcessedDf: DataFrame = MechanismOfAction(mechanismDf, targetDf, geneDf)
    val indicationProcessedDf = Indication(indicationDf, efoDf)
    val moleculeProcessedDf = Molecule(moleculeDf, drugbank2ChemblMap, context.configuration.common.inputs.drugExtensions)
    val targetsAndDiseasesDf =
      DrugCommon.getUniqTargetsAndDiseasesPerDrugId(evidenceDf).withColumnRenamed("drug_id", "id")

    logger.whenTraceEnabled {
      val columnString: DataFrame => String = _.columns.mkString("Columns: [", ",", "]")
      logger.trace(s"""Intermediate dataframes:
             Columns:
             \n\t Molecule: ${columnString(moleculeProcessedDf)},
             \n\t Indications: ${columnString(indicationDf)},
             \n\t Mechanisms: ${columnString(mechanismOfActionProcessedDf)},
             \n\t Linkages: ${columnString(targetsAndDiseasesDf)}
             Row counts:
             \n\t Molecule: ${moleculeProcessedDf.count},
             \n\t Indications: ${indicationDf.count},
             \n\t Mechanisms: ${mechanismOfActionProcessedDf.count},
             \n\t Linkages: ${targetsAndDiseasesDf.count}
             """)
    }

    logger.info(
      "Joining molecules, indications, mechanisms of action, and target and disease linkages.")

    // We define a drug as having either a drugbank id, a mechanism of action, or an indication.
    val drugMolecule = array_contains(map_keys(col("crossReferences")), "drugbank") ||
      col("indications").isNotNull ||
      col("mechanismsOfAction").isNotNull

    // using left_outer joins as we want to keep all molecules until the filter clause which defines a 'drug' for the
    // purposes of the index.
    val drugDf: DataFrame = moleculeProcessedDf
      .join(indicationProcessedDf, Seq("id"), "left_outer")
      .join(mechanismOfActionProcessedDf, Seq("id"), "left_outer")
      .join(targetsAndDiseasesDf, Seq("id"), "left_outer")
      .filter(drugMolecule)
      .transform(addDescription)

    val outputs = Seq("drugs-beta")
    logger.info(s"Writing outputs: ${outputs.mkString(",")}")

    val outputConfs =
      Helpers.generateDefaultIoOutputConfiguration(outputs: _*)(context.configuration)

    val outputDFs = (outputs zip Seq(drugDf)).toMap

    Helpers.writeTo(outputConfs, outputDFs)
  }

}
