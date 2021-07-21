package io.opentargets.etl.backend.target

import com.typesafe.scalalogging.LazyLogging
import io.opentargets.etl.backend.spark.Helpers.{nest, safeArrayUnion}
import org.apache.spark.sql.functions.{array_contains, col, collect_list, struct, typedLit}
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession, functions}

/** Maps orthologs to ensembl human gene ids */
object Ortholog extends LazyLogging {

  /**
    * @param homologyDict     Ensembl dictionary of species: ftp://ftp.ensembl.org/pub/release-100/species_EnsemblVertebrates.txt
    * @param codingProteins   Ensembl human genes coding protein and nont coding RNA from
    *                         ftp://ftp.ensembl.org/pub/release-104/tsv/ensembl-compara/homologies/homo_sapiens/Compara.104.protein_default.homologies.tsv.gz
    *                         ftp://ftp.ensembl.org/pub/release-104/tsv/ensembl-compara/homologies/homo_sapiens/Compara.104.ncrna_default.homologies.tsv.gz
    * @param homologyGeneDict mapping of gene_id to gene_name for homology genes. File generated by PIS.
    * @param targetSpecies    List of whitelisted species taken from the configuration file.
    * @return
    */
  def apply(
      homologyDict: DataFrame,
      codingProteins: DataFrame,
      homologyGeneDict: DataFrame,
      targetSpecies: List[String])(implicit sparkSession: SparkSession): Dataset[LinkedOrtholog] = {
    import sparkSession.implicits._
    logger.info("Processing homologs.")

    val homoDict = homologyDict
      .select(col("#name").as("name"),
              col("species").as("speciesName"),
              col("taxonomy_id"),
              typedLit(targetSpecies.flatMap(_.split("-").headOption)).as("whitelist"))
      .filter(array_contains(col("whitelist"), col("taxonomy_id")))

    val homoGeneDictDf =
      homologyGeneDict
        .select(functions.split(col("_c0"), "\\\\t") as "a")
        .select(col("a")(0) as "homology_gene_stable_id", col("a")(1) as "targetGeneSymbol")

    val homoDF = codingProteins
      .filter("is_high_confidence = 1")
      .join(homoDict, col("homology_species") === homoDict("speciesName"))
      .join(homoGeneDictDf, Seq("homology_gene_stable_id"))
      .select(
        col("gene_stable_id").as("id"),
        col("taxonomy_id").as("speciesId"),
        col("name").as("speciesName"),
        col("homology_type").as("homologyType"),
        col("homology_gene_stable_id").as("targetGeneId"),
        col("targetGeneSymbol"),
        col("identity").cast(DoubleType).as("queryPercentageIdentity"),
        col("homology_identity")
          .cast(DoubleType)
          .as("targetPercentageIdentity")
      )

    val groupedById = nest(homoDF, homoDF.columns.filter(_ != "id").toList, "homologues")
      .withColumnRenamed("id", "humanGeneId")
      .groupBy("humanGeneId")
      .agg(collect_list("homologues") as "homologues")

    groupedById.as[LinkedOrtholog]
  }

}

case class LinkedOrtholog(humanGeneId: String, homologues: Array[Ortholog])

case class Ortholog(speciesId: String,
                    speciesName: String,
                    homologyType: String,
                    targetGeneId: String,
                    targetGeneSymbol: String,
                    queryPercentageIdentity: Double,
                    targetPercentageIdentity: Double)
