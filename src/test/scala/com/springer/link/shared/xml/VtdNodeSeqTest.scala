package com.springer.link.shared.xml

import java.nio.charset.StandardCharsets
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import com.springer.link.shared.xml.VtdXml.{NodeSeqPool, VtdElem, VtdNodeSeq}
import org.scalatest.FunSpec
import org.scalatest.Matchers._

import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class VtdNodeSeqTest extends FunSpec {
  val elem = <a>
    <title modifier="very">hello <b>bold</b> sam</title>
    <title2 modifier="very">hello <b><c>bold</c></b> curious <b>silly</b> sam</title2>
    <list>
      <item>one</item>
      <item modifier="hello">two</item>
      <item>three</item>
    </list>
  </a>

  val elem2 = <a>
    <title modifier="very">hello<b>bold</b> sam</title>
  </a>

  val vtdElem: VtdElem = VtdXml.load(elem)

  describe("use via pool") {
    it("works in a pool") {
      val bytes: Array[Byte] = elem.buildString(true).getBytes

      val pool: NodeSeqPool = VtdXml.poolOf(5)
      val execs: ExecutorService = Executors.newFixedThreadPool(100)

      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(execs)
      val res = for {i <- 1 to 100000} yield Future {

        pool.usingElem(bytes, { elem =>
          if (i % 2 == 0)
            try ((elem \ "title" \ "@modifier").text) catch {
              case t: Throwable => t.printStackTrace(); "NOO"
            }
          else
            try ((elem \ "title" \ "@modifier").text) catch {
              case t: Throwable => t.printStackTrace(); "NOO2"
            }
        })
      }

      Await.result(Future.sequence(res), Duration(10, TimeUnit.SECONDS)).foreach(_ shouldBe "very")
    }

  }

  it("should get attribute") {
    val doc = vtdElem

    (doc \ "title" \ "@modifier").text shouldBe "very"
    (doc \\ "@modifier").map(_.text) shouldBe Seq("very", "very", "hello")
    (doc \ "title" \\ "@modifier").text shouldBe "very"
    (doc \ "_").head.attribute("modifier").get.head.text shouldBe "very"
  }

  it("should work work with A++'s Abstract") {
    val abs =
      <ArticleHeader>
        <Abstract>
          <Heading>Abstract</Heading>
          <Para ID="Par1">
            In this contribution for the Golden Jubilee issue commemorating the 50th anniversary of the Journal of Materials Science, we will discuss the challenges and opportunities of nanoporous metals and their composites as novel energy conversion materials. In particular, we will concentrate on electrical-to-mechanical energy conversion using nanoporous metal-polymer composite materials. A materials system that mimic the properties of human skeletal muscles upon an outside stimulus is coined an ‘artificial muscle.’ In contrast to piezoceramics, nanoporous metallic materials offer a unique combination of low operating voltages, relatively large strain amplitudes, high stiffness, and strength. Here we will discuss smart materials where large macroscopic strain amplitudes up to 10 % and strain-rates up to 10
            <Superscript>−2</Superscript>
            s
            <Superscript>−1</Superscript>
            can be achieved in nanoporous metal/polymer composite. These strain amplitudes and strain-rates are roughly 2 and 5 orders of magnitude larger than those achieved in common actuator materials, respectively. Continuing on the theme of energy-related applications, in the summary and outlook, we discuss two recent developments toward the integration of nanoporous metals into energy conversion and storage systems. We specifically focus on the exciting potential of nanoporous metals as anodes for high-performance water electrolyzers and in next-generation lithium-ion batteries.
          </Para>
        </Abstract>
      </ArticleHeader>

    val vtdAbs = VtdXml.load(abs)

    (abs \ "Abstract").head.child.size shouldBe 5
    (abs \ "Abstract").flatMap(_.child).map(_.label).filter(_ != "#PCDATA") shouldBe Seq("Heading", "Para")
    (abs \ "Abstract").head.child.head.label shouldBe "#PCDATA"
    (abs \ "Abstract").head.child(0).label shouldBe "#PCDATA"

    (vtdAbs \ "Abstract").head.child.size shouldBe 2
    (vtdAbs \ "Abstract").flatMap(_.child).map(_.label) shouldBe Seq("Heading", "Para") // instead: "Abstract", "Abstract"
    (vtdAbs \ "Abstract").head.child.head.label shouldBe "Heading" // instead: "Abstract"

    (vtdAbs \ "Abstract").head.child(0).label shouldBe "Heading" // instead: "Heading"
  }

  it("should get children") {
    val doc = vtdElem

    val seq = (doc \ "title2").head
    val child = seq.child

    child.length shouldBe 5
    child.map(_.text).mkString shouldBe "hello bold curious silly sam"
    //    child.mkString shouldBe "hello <b><c>bold</c></b> curious <b>silly</b> sam" //todo

    child.head.text shouldBe "hello "
    child(1).text shouldBe "bold"
    child(2).text shouldBe " curious "
    child(3).text shouldBe "silly"
    child.last.text shouldBe " sam"
  }

  it("sanitize title bug - use of lable in map truncates nodeseq") {
    val example = VtdXml.load(<ArticleTitle xml:lang="en" Language="En">
      Observation of a new boson with mass near 125 GeV in pp collisions at
      <InlineEquation ID="IEq1">
        <InlineMediaObject>
          <ImageObject Type="Linedraw" Rendition="HTML" Format="GIF" Color="BlackWhite" FileRef="https://static-content.springer.com/image/art%3A10.1007%2FJHEP06%282013%29081/13130_2013_6284_Article_IEq1.gif"/>
        </InlineMediaObject>
        <EquationSource Format="TEX">$ \sqrt{}=7 $</EquationSource>
      </InlineEquation>
      and 8 TeV
    </ArticleTitle>)

    example.child.map {
      case childNode if childNode.label == "InlineEquation" => ""
      case childNode => childNode.text
    }.size shouldBe 3

  }

  it("should get attribute using \\@") {
    val keyword = <ArticleHeader>
    <KeywordGroup xml:lang="en" OutputMedium="All" Language="En">
    </KeywordGroup>
    </ArticleHeader>

    (VtdXml.load(keyword) \ "KeywordGroup" \@ "OutputMedium") shouldBe "All"
  }

  it("keywords bug - * text matches elems. Use nav.getTokenType(nextLoc) instead") {
    val keyword = <ArticleHeader>
    <KeywordGroup xml:lang="en" OutputMedium="All" Language="En">
      <Heading>Keywords</Heading>
      <Keyword>
        <Emphasis Type="Italic">L</Emphasis>*<Emphasis Type="Italic">a</Emphasis>*<Emphasis Type="Italic">b</Emphasis>*</Keyword>
      <Keyword>Spectrophotometer</Keyword>
      <Keyword>Colour evaluation</Keyword>
    </KeywordGroup>
    </ArticleHeader>

    val vtdKeywords = VtdXml.load(keyword)

    (vtdKeywords \ "KeywordGroup" \ "Keyword").map { elem =>
      elem.child.find(_.label == "IndexTerm").map(_ \ "Primary").getOrElse(elem).text.trim
    } shouldBe List("""L*a*b*""".stripMargin, "Spectrophotometer", "Colour evaluation")
  }

  it("should get label") {
    val doc = vtdElem

    (doc \ "title" \ "@modifier").head.text shouldBe "very"
    (doc \ "title").head.label shouldBe "title" //?hmm - not sure about that one
  }

  it("elems should behave like queries") {
    val doc = vtdElem

    val seq1 = doc \ "list" \ "item"
    seq1.text shouldBe "onetwothree"
    seq1.head.text shouldBe "one"
    seq1(1).text shouldBe "two"
    seq1.last.text shouldBe "three"

    //    seq1.mkString shouldBe "<item>one</item><item modifier=\"hello\">two</item><item>three</item>" //todo

    val seq = doc \ "list"
    seq.length shouldBe 1
    seq.text.split("\n").map(_.trim()).mkString shouldBe "onetwothree"
    seq.iterator.next().text /*..split("\n").map(_.trim()).mkString */ shouldBe "onetwothree" // "\n      one\n      two\n      three\n    "

    seq.mkString shouldBe "<list>\n      <item>one</item>\n      <item modifier=\"hello\">two</item>\n      <item>three</item>\n    </list>"
    (seq \\ "@modifier").text shouldBe "hello"

    (doc \ "list" \\ "@modifier").exists(n => n.exists(_.text == "hello")) shouldBe true

    (doc \ "list").exists(n => {
      (n \\ "@modifier").text == "hello"
    }) shouldBe true
  }

  it("// gets content at different levels") {
    val elem = VtdXml.load(
      <a>
        <b>1</b>
        <sub><b>2</b></sub>
        <b>3</b>
    </a>)

    val bs = (elem \\ "b").map(x => x.mkString)

    bs.length shouldBe 3
    bs.filterNot(_ == "").length shouldBe 3
  }

  it("should return text of nodeseq and each node") {
    val seq = vtdElem \ "title2"
    seq.length shouldBe 1
    seq.text shouldBe "hello bold curious silly sam"

    seq.head.text shouldBe "hello bold curious silly sam"

    seq.map(_.text).mkString shouldBe "hello bold curious silly sam"
    seq.mkString shouldBe "<title2 modifier=\"very\">hello <b><c>bold</c></b> curious <b>silly</b> sam</title2>"
  }


  it("supports xpath functions") {
    val elem = <a>
      <list>
        <item>10.0</item>
        <item>9.0</item>
        <item>100.0</item>
      </list>
    </a>

    val number: Double = VtdXml.load(elem).evalXpathToNumber(_ => "sum(//list/item)")
    number shouldBe 119.0
  }

  it("""supports xpath in between \'s """) {
    val elem = <a>
      <list>
        <item>1</item>
        <item>2</item>
        <item>3</item>
        <item>4</item>
        <item>5</item>
      </list>
    </a>

    val seq: VtdNodeSeq = VtdXml.load(elem) \ "list" \ "item[3 > .]"
    seq.size shouldBe 2
  }

  describe("payload") {
    it("provides byte array containing the xml element and all its content, including text and other sub-elements") {
      val elem = <a><list><item>10.0</item></list></a>
      new String(VtdXml.load(elem).payload, StandardCharsets.UTF_8) shouldBe """<a><list><item>10.0</item></list></a>"""
    }

    it("provides byte array containing selected sub-element") {
      val xml = <a><list><item>10.0</item></list></a>
      val vtdXml = VtdXml.load(xml) \ "list"
      new String(vtdXml.payload, StandardCharsets.UTF_8) shouldBe """<list><item>10.0</item></list>"""
    }

    it("provides empty byte array when no element is selected") {
      val xml = <a><list><item>10.0</item></list></a>
      val vtdXml = VtdXml.load(xml) \ "foo"
      new String(vtdXml.payload, StandardCharsets.UTF_8) shouldBe ""
    }

    it("provides byte array for the first element if more than one is selected") {
      val xml = <a><list><item>10.0</item><item>11.0</item></list></a>
      val vtdXml = VtdXml.load(xml) \ "list" \ "item"
      new String(vtdXml.payload, StandardCharsets.UTF_8) shouldBe "<item>10.0</item>"
    }
  }
}
