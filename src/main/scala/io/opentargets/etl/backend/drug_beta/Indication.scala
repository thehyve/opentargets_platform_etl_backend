package io.opentargets.etl.backend.drug_beta

import com.typesafe.scalalogging.LazyLogging
import io.opentargets.etl.backend.spark.Helpers
import io.opentargets.etl.backend.spark.Helpers.nest
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions._

/**
  * Class to process ChEMBL indications for incorporation into Drug.
  *
  * Output schema:
  * id
  * indications
  * -- count
  * -- rows
  * ---- disease
  * ---- maxPhaseForIndication
  * ---- references
  * ------ source
  * ------ ids
  * ------ urls
  * indication_therapeutic_areas
  * -- therapeutic_code
  * -- therapeutic_label
  * -- count
  */
class Indication(indicationsRaw: DataFrame, efoRaw: DataFrame)(implicit sparkSession: SparkSession)
    extends LazyLogging {
  import sparkSession.implicits._

  def processIndications: DataFrame = {
    logger.info("Processing indications.")
    // efoDf for therapeutic areas
    val efoDf = getEfoDataframe(efoRaw).transform(formatEfoIds)
    val indicationAndEfoDf = processIndicationsRawData
      .join(efoDf, Seq("efo_id"), "leftouter")

    val indicationDf: DataFrame = indicationAndEfoDf
      .withColumn("struct",
                  struct($"efo_id".as("disease"),
                         $"max_phase_for_indications".as("maxPhaseForIndication"),
                         $"references"))
      .groupBy("id")
      .agg(collect_list("struct").as("rows"))
      .withColumn("count", size($"rows"))
      .transform(nest(_: DataFrame, List("rows", "count"), "indications"))

    indicationDf
  }

  private def processIndicationsRawData: DataFrame = {
    val df = formatEfoIds(this.indicationsRaw)

    // flatten hierarchy
    df.withColumn("r", explode($"indication_refs"))
      .select($"molecule_chembl_id".as("id"),
              $"efo_id",
              $"max_phase_for_ind",
              $"r.ref_id",
              $"r.ref_type",
              $"r.ref_url")
      // remove indications we can't link to a disease.
      .filter($"efo_id".isNotNull)
      // handle case where clinical trials packs multiple ids into a csv string
      .withColumn("ref_id", split($"ref_id", ","))
      .withColumn("ref_id", explode($"ref_id"))
      // group reference ids and urls by ref_type
      .groupBy("id", "efo_id", "ref_type")
      .agg(max("max_phase_for_ind").as("max_phase_for_ind"),
           collect_list("ref_id").as("ids"),
           collect_list("ref_url").as("urls"))
      // nest references and find max_phase
      .withColumn("references",
                  struct(
                    $"ref_type".as("source"),
                    $"ids",
                    $"urls"
                  ))
      .groupBy("id", "efo_id")
      .agg(
        max("max_phase_for_ind").as("max_phase_for_indications"),
        collect_list("references").as("references")
      )

  }

  /**
    *
    * @param rawEfoData taken from the `disease` input data
    * @return dataframe of `efo_id`, `efo_label`, `efo_uri`, `therapeutic_codes`, `therapeutic_labels`
    */
  private def getEfoDataframe(rawEfoData: DataFrame): DataFrame = {
    val columnsOfInterest = Seq(("code", "efo_url"),
                                ("label", "efo_label"),
                                ("therapeutic_codes", "therapeutic_codes"),
                                ("therapeutic_labels", "therapeutic_labels"))
    val df = rawEfoData
      .select(columnsOfInterest.map(_._1).map(col): _*)
      .withColumn("efo_id", Helpers.stripIDFromURI(col("code")))
    // rename columns
    columnsOfInterest.foldLeft(df)((d, names) => d.withColumnRenamed(names._1, names._2))
  }

  /**
    *
    * @param indicationDf dataframe of ChEMBL indications
    * @return dataframe with efo_ids in form EFO_xxxx instead of EFO:xxxx
    */
  private def formatEfoIds(indicationDf: DataFrame): DataFrame = {
    indicationDf.withColumn("efo_id", regexp_replace(col("efo_id"), ":", "_"))
  }

}

object Indication extends Serializable with LazyLogging {

}
