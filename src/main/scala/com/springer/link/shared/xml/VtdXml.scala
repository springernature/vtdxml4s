package com.springer.link.shared.xml

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.util.concurrent.{ArrayBlockingQueue, LinkedBlockingDeque}

import com.ximpleware.{AutoPilot, VTDGen, VTDNav, XPathParseException}

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
      vg.clear()
      vg.setDoc_BR(bytes)
      vg.parse(false)
      val nav: VTDNav = vg.getNav
      new VtdElem(vg, nav, List(new XpathStep("/" + nav.toRawString(nav.getRootIndex))))
    }

    def release(elem: VtdElem): Unit = {
      stack.addFirst(elem.vg)
    }
  }

  sealed class VtdNodeSeq(nav: VTDNav, private val xpathParts: List[XpathStep] = List.empty, val attrName: Option[String] = None, asChild: Boolean = false) extends Seq[VtdNode] {
    def \@(attributeName: String): String = (this \ ("@" + attributeName)).text

    override def iterator: Iterator[VtdNode] = new AbstractIterator[VtdNode] {
      val cloneNav = nav.cloneNav()
      val auto = new AutoPilot(cloneNav)

      val expression: String = xpathParts.mkString

      if (asChild) {
        auto.selectXPath(expression + "/node()")
      } else {
        auto.selectXPath(expression)
      }

      var nextLoc: Int = auto.evalXPath()
      var nodeCounter = 0
      var elemCounter = 0

      override def hasNext: Boolean = nextLoc > -1

      override def next(): VtdNode = {
        if (nextLoc == -1) throw new NoSuchElementException

        nodeCounter = nodeCounter + 1
        val elem: VtdNode = if (attrName.isDefined) attributeText(cloneNav)
        else {
          val maybeText: String = cloneNav.toRawString(nextLoc)

          val namespace: Int = maybeText.indexOf(":")
          val maybeLabel = if (namespace > -1) maybeText.substring(namespace + 1) else maybeText

          if (cloneNav.getText == -1 && cloneNav.getTokenType(nextLoc) != VTDNav.TOKEN_STARTING_TAG) {
            new VtdTextElem(cloneNav, xpathParts, None, maybeText)
          } else {
            val vg: VTDGen = new VTDGen()

            if (asChild) {
              val countNav = nav.cloneNav()
              val countAuto = new AutoPilot(countNav)

              val isElem: Boolean = countNav.getTokenType(nextLoc) != VTDNav.TOKEN_STARTING_TAG
              if (nav.getText > -1 || isElem) elemCounter = elemCounter + 1

              childNodeSeq(vg, cloneNav, maybeLabel, elemCounter, nodeCounter)
            } else {
              val (fragment, xml, step) = (cloneNav.getElementFragment, cloneNav.getXML, new XpathStep("/" + maybeLabel))
              val offset = fragment.asInstanceOf[Int]
              val len = (fragment >>> 32).asInstanceOf[Int]

              vg.clear()
              vg.setDoc_BR(xml.getBytes, offset, len)
              vg.parse(false) //no namespaces

              new VtdElem(vg, vg.getNav, List(step))
            }
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

        if (nextLoc > -1 && nav.getText == -1 && nav.getTokenType(nextLoc) != VTDNav.TOKEN_STARTING_TAG)
          new VtdTextElem(nav, xpathParts, None, label)
        else {
          auto.selectXPath(xpathParts.mkString + "/node()")
          def numOfElems: Int = {
            var nextLoc: Int = auto.evalXPath()
            var count = 0
            var elemCount = 0

            while (nextLoc != -1 && count < idx) {
              val string: String = nav.toRawString(nextLoc)
              val isElem: Boolean = nav.getTokenType(nextLoc) != VTDNav.TOKEN_STARTING_TAG
              if (nav.getText > -1 || isElem) elemCount = elemCount + 1
              nextLoc = auto.evalXPath()
              count = count + 1
            }
            elemCount
          }

          childNodeSeq(new VTDGen(), nav, label, length, numOfElems)
        }
      } else {
        auto.selectXPath(xpathParts.mkString + "[" + (idx + 1) + "]")
        val nextLoc: Int = auto.evalXPath()
        val label: String = nav.toRawString(nextLoc)
        val (fragment, xml, step) = (nav.getElementFragment, nav.getXML, new XpathStep("/" + label))
        val offset = fragment.asInstanceOf[Int]
        val len = (fragment >>> 32).asInstanceOf[Int]

        val vg = new VTDGen
        vg.clear()
        vg.setDoc_BR(xml.getBytes, offset, len)
        vg.parse(false) //no namespaces

        new VtdElem(vg, vg.getNav, List(step))
      }
    }

    private def attributeText(attrNav: VTDNav): VtdTextElem = {
      val attrVal: Int = attrNav.getAttrVal(attrName.get)
      new VtdTextElem(nav, xpathParts, attrName, if (attrVal > -1) nav.toNormalizedString(attrVal) else "")
    }

    private def childNodeSeq(vg: VTDGen, nav: VTDNav, label: String, numOfNodes: Int, elemCount: Int): VtdElem = {
      val (fragment, xml, step) = if (numOfNodes > 1)
        (nav.getElementFragment, nav.getXML, new XpathStep("/" + label))
      else {
        val parentNav: VTDNav = nav.cloneNav()
        parentNav.toElement(1) //get the parent

        val parent: String = parentNav.toRawString(parentNav.getCurrentIndex)
        (parentNav.getElementFragment, parentNav.getXML, new XpathStep("/" + parent, "/" + parent + "/node()[" + elemCount + "]"))
      }

      val offset = fragment.asInstanceOf[Int]
      val len = (fragment >>> 32).asInstanceOf[Int]

      vg.clear()
      vg.setDoc_BR(xml.getBytes, offset, len)
      vg.parse(false) //no namespaces

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

    override def mkString = {
      new String(payload)
    }

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
      if(auto.evalXPath() != -1)
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
