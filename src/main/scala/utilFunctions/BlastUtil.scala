package utilFunctions

import java.io.{File, PrintWriter}
import java.util

import org.apache.logging.log4j.LogManager
import org.neo4j.graphdb.{ResourceIterator, DynamicLabel, Node, GraphDatabaseService}
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import scala.collection.JavaConverters._
import scala.io.Source
import scala.sys.process.Process
import utilFunctions.TransactionSupport
/**
  * Created by artem on 27.03.16.
  */

class WorkWithGraph(pathToDataBase: String) extends TransactionSupport {
  val dataBaseFile = new File(pathToDataBase)
  val graphDataBaseConnection = new GraphDatabaseFactory().newEmbeddedDatabase(dataBaseFile)

  def getAllNodesByLabel(requiredLabel: String): ResourceIterator[Node] = transaction(graphDataBaseConnection) {
    val resultNodes = graphDataBaseConnection.findNodes(DynamicLabel.label(requiredLabel))
    resultNodes
  }
}


class BlastUtil(pathToDataBase: String) extends WorkWithGraph(pathToDataBase) {

  val logger = LogManager.getLogger(this.getClass.getName)
  logger.info("BlastUtil object is initialized.")

  def getAllSequencesNodes = getAllNodesByLabel("Sequence")

  def applyOperationToNodes(nodeIterator: ResourceIterator[Node])
                           (makeSomethingWithNodes: (Node) => Unit): Unit = transaction(graphDataBaseConnection){
    logger.debug("Transaction in applyOperationToNodes")
    nodeIterator.asScala.foreach(makeSomethingWithNodes)
  }

  def writeNodesInfoToFile(nodeIterator: ResourceIterator[Node], filename: PrintWriter)
                           (nodesInfoWriter: (Node, PrintWriter) => Unit): Unit = transaction(graphDataBaseConnection){
    logger.debug("Transaction in writeNodesInfoToFile")
    nodeIterator.asScala.foreach(nodesInfoWriter(_, filename))
  }

  def makeSequencesFastaFile(
                   nodeIterator : ResourceIterator[Node],
                   outputFastaFileName: String) = {
    val outputFastaFile = new PrintWriter(outputFastaFileName)
    def writeSequencesIntoFastaFile(sequenceNode: org.neo4j.graphdb.Node, outputFastaFile: PrintWriter): Unit = {
      val sequenceId = sequenceNode.getId.toString
      val sequenceSeq = sequenceNode.getProperty("seq").toString
      outputFastaFile.write(">" + sequenceId + "\n" + sequenceSeq + "\n")
    }
    writeNodesInfoToFile(nodeIterator, outputFastaFile)(writeSequencesIntoFastaFile)
    outputFastaFile.close()
  }

  def callUblast(ublastLocation: String)(ublastCommand: String): Unit = {
    val ublastResultOutput = Process(ublastLocation + ublastCommand).!!
    println(ublastResultOutput)
  }

  def makeUDB(ublastLocation: String, fastaFileLocation: String, udbFileLocation: String): Unit = {
    callUblast(
      ublastLocation)(
      " -makeudb_ublast " +
      fastaFileLocation +
      " -output " +
      udbFileLocation
    )
  }

  def makeUblast(
                  ublastLocation: String,
                  fastaInputFileLocation: String,
                  udbFileLocation: String,
                  outputTextFile: String): Unit = {
    val command =
        " -ublast " +
        fastaInputFileLocation +
        " -db " + udbFileLocation +
        " -evalue 1e-5" + " -mid 70.0" +
        " -self" +
        " -minqt 0.5" +
        " -maxaccepts 500" +
        " -userout " +
        outputTextFile +
        " -userfields query+id+tseq+target+qs+ts+qlor+qhir+tlor+thir+evalue+qseq"
    callUblast(ublastLocation)(command)
  }

  def createSimilarRelationshipsFromInsideBlast(blastOutputFilename: String, dropSize: Int): Int = transaction(graphDataBaseConnection){
//    val iteratorSize = Source.fromFile(blastOutputFilename).getLines().size
    logger.debug("Transaction in createSimilarRelationshipsFromInsideBlast")

    val source = Source.fromFile(blastOutputFilename)
    val fullReadFileIterator = source.getLines()
    val currentIterator = fullReadFileIterator.drop(dropSize)

    def parseString(currentString: String): List[String] = {
      val splitString = currentString.split('\t')
      val querySeqId: String = splitString(0)
      val querySeq: String = splitString.last
      val targetSeq: String = splitString(2)
      val targetSeqId: String = splitString(3)
      val evalue: String = splitString(10)
      val identity: String = splitString(1)
      List(
        querySeq,
        querySeqId,
        querySeq,
        targetSeq,
        targetSeqId,
        evalue,
        identity
      )
    }

    def createInnerBlastSimilarRelationship(lineList: List[String]): Unit = {
//      logger.debug("Get the node with id:" + lineList(1))
      val querySeqNode = graphDataBaseConnection.getNodeById(lineList(1).toLong)
//      logger.debug("Get the node with id:" + lineList(4))
      val targetSeqNode = graphDataBaseConnection.getNodeById(lineList(4).toLong)
//      println(querySeqNode.getProperty("md5"), targetSeqNode.getProperty("md5"))
      if (!utilFunctionsObject.checkRelationExistenceWithDirection(querySeqNode, targetSeqNode)) {
        val similarRelationship = querySeqNode.createRelationshipTo(targetSeqNode, BiomeDBRelations.similar)
        similarRelationship.setProperty("evalue", lineList(5).toDouble)
        similarRelationship.setProperty("identity", lineList(6).toDouble)
//        println("Created relationship.")
      }
    }

    def createInnersBlastRelationships(currentString: String): Unit = {
      val lineList = parseString(currentString)
      createInnerBlastSimilarRelationship(lineList)
    }

    logger.debug("Transaction in 500000 nodes start")
    currentIterator.take(500000).foreach(createInnersBlastRelationships)
    println("Number of lines: " + dropSize)
    logger.debug("Transaction in 500000 nodes finish")
//    if (dropSize < iteratorSize) createSimilarRelationshipsFromInsideBlast(blastOutputFilename, dropSize + 500000)
    dropSize
  }

  def makeInnerBlast(blastOutputFilename: String, dropSize: Int): Unit = {
    val iteratorSize = Source.fromFile(blastOutputFilename).getLines().size
    logger.debug("Number of lines: " + iteratorSize)
//    val res = createSimilarRelationshipsFromInsideBlast(blastOutputFilename, dropSize)
//    if (res < iteratorSize) createSimilarRelationshipsFromInsideBlast(blastOutputFilename, res)
    def loop(res: Int): Unit = {
      val nextRes = createSimilarRelationshipsFromInsideBlast(blastOutputFilename, res)
      if (nextRes < iteratorSize) loop(nextRes + 500000)
    }
    loop(0)
  }

}
