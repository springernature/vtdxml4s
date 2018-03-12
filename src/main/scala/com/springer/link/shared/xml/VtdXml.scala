package com.springer.link.shared.xml

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.util.concurrent.LinkedBlockingDeque

import com.ximpleware._

import scala.collection.AbstractIterator
import scala.xml.Elem

object VtdXml {

  def load(str: String): VtdElem = load(str.getBytes)

  def load(elem: Elem): VtdElem = load(elem.buildString(stripComments = true))

  def load(is: InputStream): VtdElem = {
    def copy(input: InputStream, output: OutputStream): Unit = {
      val buffer = new Array[Byte](4 * 1024)
      var n = 0
      while ( {
        n = input.read(buffer)
        n > -1
      }) {
        output.write(buffer, 0, n)
      }
    }

    val output: ByteArrayOutputStream = new ByteArrayOutputStream()
    copy(is, output)
    load(output.toByteArray)
  }

  def load(bytes: Array[Byte]): VtdElem = {
    val vg: VTDGen = new VTDGen()
    vg.selectLcDepth(5)
    vg.setDoc(bytes)
    vg.parse(false) //no namespaces

    val nav: VTDNav = vg.getNav
    new VtdElem(vg, nav, List(new XpathStep("/" + nav.toRawString(nav.getRootIndex))))
  }

  def poolOf(size: Int): NodeSeqPool = new NodeSeqPool(size)

  class NodeSeqPool(val size: Int) {
    val stack = new LinkedBlockingDeque[VTDGen](size)

    (1 to size).foreach { _ =>
      val vg: VTDGen = new VTDGen()
      vg.selectLcDepth(5)
      stack.put(vg)
    }

    def usingElem[T](bytes: Array[Byte], blk: VtdElem => T): T = {
      val elem: VtdElem = take(bytes)
      try {
        blk(elem)
      } finally {
        release(elem)
      }
    }

    def take(bytes: Array[Byte]): VtdElem = {
      val vg: VTDGen = stack.takeFirst()
      try {
        vg.clear()
        vg.setDoc_BR(bytes)
        vg.parse(false)
        val nav: VTDNav = vg.getNav
        new VtdElem(vg, nav, List(new XpathStep("/" + nav.toRawString(nav.getRootIndex))))
      } catch {
        case t: Throwable =>
          stack.addFirst(vg)
          throw t
      }
    }

    def release(elem: VtdElem): Unit = {
      stack.addFirst(elem.vg)
    }
  }

  sealed class VtdNodeSeq(nav: VTDNav, private val xpathParts: List[XpathStep] = List.empty, attrName: Option[String] = None, asChild: Boolean = false) extends Seq[VtdNode] {
    def \@(attributeName: String): String = (this \ ("@" + attributeName)).text

    override def iterator: Iterator[VtdNode] = new AbstractIterator[VtdNode] {
      val cloneNav: VTDNav = nav.cloneNav()
      val auto = new AutoPilot(cloneNav)

      val expression: String = xpathParts.mkString

      if (asChild) {
        auto.selectXPath(expression + "/node()")
      } else {
        auto.selectXPath(expression)
      }

      var nextLoc: Int = auto.evalXPath()

      override def hasNext: Boolean = nextLoc > -1

      override def next(): VtdNode = {
        if (nextLoc == -1) throw new NoSuchElementException
        val elem: VtdNode = if (attrName.isDefined) attributeText(cloneNav)
        else {
          val maybeText: String = cloneNav.toRawString(nextLoc)

          val namespaceIdx: Int = maybeText.indexOf(":")
          val (ns, parentLabel) = if (namespaceIdx > -1) Some(maybeText.substring(0, namespaceIdx)) -> maybeText.substring(namespaceIdx + 1) else None -> maybeText

          if (cloneNav.getText == -1 && cloneNav.getTokenType(nextLoc) != VTDNav.TOKEN_STARTING_TAG) {
            new VtdTextElem(cloneNav, xpathParts, None, maybeText)
          } else {
            childNodeSeq(cloneNav, parentLabel, ns)
          }
        }
        nextLoc = auto.evalXPath()
        elem
      }
    }

    override def apply(idx: Int): VtdNode = {
      if (idx >= length) throw new NoSuchElementException

      val auto = new AutoPilot(nav)
      if (attrName.isDefined) {
        auto.selectXPath(xpathParts.mkString + "[" + (idx + 1) + "]")
        auto.evalXPath()
        attributeText(nav)
      } else if (asChild) {
        auto.selectXPath(xpathParts.mkString + "/node()[" + (idx + 1) + "]")
        val nextLoc: Int = auto.evalXPath()
        val label: String = nav.toRawString(nextLoc)
        val namespaceIdx: Int = label.indexOf(":")
        val (ns, parentLabel) = if (namespaceIdx > -1) Some(label.substring(0, namespaceIdx)) -> label.substring(namespaceIdx + 1) else None -> label


        if (nextLoc > -1 && nav.getText == -1 && nav.getTokenType(nextLoc) != VTDNav.TOKEN_STARTING_TAG)
          new VtdTextElem(nav, xpathParts, None, label)
        else {
          childNodeSeq(nav, parentLabel, ns)
        }
      } else {
        auto.selectXPath(xpathParts.mkString + "[" + (idx + 1) + "]")
        val nextLoc: Int = auto.evalXPath()
        val label: String = nav.toRawString(nextLoc)
        val namespaceIdx: Int = label.indexOf(":")
        val (ns, parentLabel) = if (namespaceIdx > -1) Some(label.substring(0, namespaceIdx)) -> label.substring(namespaceIdx + 1) else None -> label

        childNodeSeq(nav, parentLabel, ns)
      }
    }

    private def attributeText(attrNav: VTDNav): VtdTextElem = {
      val attrVal: Int = attrNav.getAttrVal(attrName.get)
      new VtdTextElem(nav, xpathParts, attrName, if (attrVal > -1) nav.toNormalizedString(attrVal) else "")
    }

    private def childNodeSeq(nav: VTDNav, elemName: String, namespace: Option[String]) = {
      val vg = new VTDGen()
      val fragment = nav.getElementFragment
      val xml = nav.getXML
      val step = new XpathStep("/" + elemName)

      val offset = fragment.asInstanceOf[Int]
      val len = (fragment >>> 32).asInstanceOf[Int]

      //Fix for weird bug with pi
      val bytes = xml.getBytes

      vg.setDoc(bytes, offset, len)
      try {
        vg.parse(false) //no namespaces
      } catch {
        case _: ParseException =>
          vg.clear()
          val str = new String(bytes, offset, len)
          val closeTagRegex = s"</$elemName>|<$elemName ?.*/>".r
          val inner = str.substring(0, closeTagRegex.findFirstMatchIn(str).get.end)
          vg.setDoc_BR(inner.getBytes)
          vg.parse(false)
      }


      new VtdElem(vg, vg.getNav, List(step))
    }

    def evalXpathToNodeSeq(xpath: String => String): VtdNodeSeq = new VtdNodeSeq(nav, List(new XpathStep(xpath(mkExpression))), attrName)

    def evalXpathToNumber(xpath: String => String): Double = {
      val auto = new AutoPilot(nav)
      val expression = xpath(mkExpression)
      try {
        auto.selectXPath(expression)
        val count: Int = auto.evalXPathToNumber().toInt
        if (count == -1) 0 else count
      } catch {
        case t: XPathParseException => throw new RuntimeException(s"xpath $expression error", t)
      }
    }

    def evalXpathToBoolean(xpath: String => String): Boolean = {
      val auto = new AutoPilot(nav)
      val expression = xpath(mkExpression)
      try {
        if (attrName.isDefined) {
          throw new IllegalArgumentException(s"Not a valid xpath expression $expression")
        } else {
          auto.selectXPath(expression)
          auto.evalXPathToBoolean()
        }
      }

      catch {
        case t: XPathParseException => throw new RuntimeException(s"xpath $expression error", t)
      }
    }

    def evalXpathToString(xpath: String => String): String = {
      val auto = new AutoPilot(nav)
      val expression = xpath(mkExpression)
      try {
        auto.selectXPath(expression)
        auto.evalXPathToString()
      } catch {
        case t: XPathParseException => throw new RuntimeException(s"Bad xpath $expression", t)
      }
    }

    override lazy val length: Int = if (asChild)
      evalXpathToNumber(a => "count(" + a + "/node())").toInt
    else
      evalXpathToNumber(a => "count(" + a + ")").toInt

    lazy val text: String = {
      val auto = new AutoPilot(nav)
      val expression = mkExpression
      try {
        auto.selectXPath(expression)
        auto.evalXPathToString()
        val next: Int = auto.evalXPath()
        if (next == -1) ""
        else {
          if (attrName.isDefined) {
            val attrVal: Int = nav.getAttrVal(attrName.get)
            if (attrVal > -1) nav.toRawString(attrVal) else ""
          } else {
            auto.selectXPath(expression)
            val buf = new StringBuilder
            var path: Int = auto.evalXPath()
            while (path != -1) {
              buf.append(nav.getXPathStringVal)
              path = auto.evalXPath()
            }
            buf.toString
          }
        }
      } catch {
        case t: XPathParseException => throw new RuntimeException(s"Bad xpath $expression", t)
      }
    }

    override def mkString = new String(payload)

    override def toString(): String = text

    def \(path: String): VtdNodeSeq = path match {
      case attr if attr.head == '@' => new VtdNodeSeq(nav, xpathParts :+ new XpathStep("[" + attr + "]"), Some(attr.substring(1)))
      case "_" => new VtdNodeSeq(nav, xpathParts :+ new XpathStep("/*"))
      case _ => new VtdNodeSeq(nav, xpathParts :+ new XpathStep("/" + path))
    }

    def \\(path: String): VtdNodeSeq = path match {
      case attr if attr.head == '@' => new VtdNodeSeq(nav, xpathParts :+ new XpathStep("/descendant-or-self::*[" + attr + "]"), Some(attr.substring(1)))
      case "_" => new VtdNodeSeq(nav, xpathParts :+ new XpathStep("//*"))
      case _ => new VtdNodeSeq(nav, xpathParts :+ new XpathStep("//" + path))
    }

    def mkExpression: String = xpathParts.reverse.tail.reverse.mkString + xpathParts.last.expressionForText

    // We would expect this method to return a concatenation of all payloads if more than one element was selected,
    // instead, it returns only the payload of the first element.
    // We did not change this to maintain back-compatibility.
    def payload: Array[Byte] = {
      val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
      val auto = new AutoPilot(nav)
      val string: String = xpathParts.mkString
      auto.selectXPath(string)
      if (auto.evalXPath() != -1)
        nav.dumpFragment(stream)

      stream.toByteArray
    }
  }

  private[xml] class XpathStep(val expressionForNode: String, val expressionForText: String) {
    def this(both: String) = this(both, both)

    override def toString: String = expressionForNode
  }

  sealed class VtdNode(nav: VTDNav, private val xpathParts: List[XpathStep] = List.empty, attrName: Option[String] = None) extends VtdNodeSeq(nav, xpathParts, attrName) {
    def attribute(name: String): Option[Seq[VtdNode]] = {
      Some(new VtdNodeSeq(nav, xpathParts, Some(name)))
    }

    def child: VtdNodeSeq = new VtdNodeSeq(nav, xpathParts, attrName, true)

    def label: String = {
      val clonedNav = nav.cloneNav()
      val auto = new AutoPilot(clonedNav)
      auto.selectXPath(mkExpression)
      auto.evalXPath()
      clonedNav.toRawString(clonedNav.getCurrentIndex)
    }
  }

  final class VtdElem(private[xml] val vg: VTDGen, nav: VTDNav, private val xpathParts: List[XpathStep] = List.empty, attrName: Option[String] = None) extends VtdNode(nav, xpathParts, attrName) {
    override def toString: String = text
  }

  final class VtdTextElem(nav: VTDNav, private val xpathParts: List[XpathStep] = List.empty, attrName: Option[String], val theText: String) extends VtdNode(nav, xpathParts, attrName) {
    override lazy val text: String = theText

    override def toString: String = text

    override def \(path: String): VtdNodeSeq = new EmptyNodeSeq(nav, xpathParts, attrName, theText)

    override def \\(path: String): VtdNodeSeq = new EmptyNodeSeq(nav, xpathParts, attrName, theText)
  }

  final class EmptyNodeSeq(nav: VTDNav, private val xpathParts: List[XpathStep] = List.empty, attrName: Option[String], val theText: String) extends VtdNodeSeq(nav, xpathParts, attrName) {
    override lazy val text: String = theText

    override def iterator: Iterator[VtdElem] = Iterator.empty

    override lazy val length: Int = 0

    override def apply(idx: Int): VtdElem = throw new NoSuchElementException
  }

}
