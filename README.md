VTD-XML [![Build Status](https://travis-ci.org/springernature/vtdxml4s.svg?branch=master)](https://travis-ci.org/springernature/vtdxml4s) [![](https://jitpack.io/v/springernature/vtdxml4s.svg)](https://jitpack.io/#springernature/vtdxml4s)
===

A drop in replacement for scala-xml with improved performance. It uses [VtdXml](http://vtd-xml.sourceforge.net "Vtd Xml Homepage") to parse and query XML 

## Supported platforms
- Scala 2.11 and 2.12
- Vtd-xml 2.13

## How to install
- sbt: 
```
resolvers += "jitpack" at "https://jitpack.io",
libraryDependencies += "com.github.springernature" %% "vtdxml4s" % "v1.0"	
```
 
## Licensing
GNU General Public License v2.0

## Maintenance
Submit issues and PR's to this github.

### How to use
When turning strings or bytes into Xml to make queries against: replace imports to use VtdXml rather than ScalaXml:

```
//import scala.xml.{NodeSeq, Node, Elem}
import com.springer.link.shared.xml.VtdXml.{VtdNodeSeq => NodeSeq, VtdNode => Node, VtdElem => Elem}     
```

Then use VtdXml#fromString, VtdXml#fromBytes, VtdXml#fromElem, VtdXml#fromInputStream to parse. 
For best performance and thread-safety it is recommended to use the fromPool and using(...)

### Extras
Because the underlying library support xpath 1.0 you can include xpath expressions between \ or \\\\ e.g.

```
    val elem = <a>
    <list>
        <item>1</item>
        <item>2</item>
        <item>3</item>
        <item>4</item>
        <item>5</item>
    </list>
    </a>                                                                                                                                        
    println(VtdXml.fromElem(elem) \ "list" \ "item[3 > .]").size() 
    //2   
```

There are also extra methods you can use to push logic into xpath which can also improve performance e.g.

```
    val elem = <a>
    <list>
       <item>10.0</item>
       <item>9.0</item>
       <item>100.0</item>
     </list>
    </a>
    val number: Double = VtdXml.fromElem(elem).evalXpathToNumber(_ => "sum(//list/item)")
    //119.0
```

### When to use
I have not bench-marked, but I have noticed significant improvements in:

- parsing large documents; Xml.load is much slower than the vtd-xml equivalent(s),
- \\\\ for large documents can be very slow in scala-xml. It's much faster in vtd-xml,
- size; vtd-xml indexes are typically 50% the size of the bytes of the document. Scala xml can be many times larger than the bytes 
   * e.g.(2.4M document was a total of 3.3M in vtd-xml and > 12M for scala xml) 
   
I have not noticed improvement when:

- Xml documents are small
- Pattern of use it to iterate over large nodeseq and map it to scala objects. e.g. for document with many authors the following is still disappointingly slow: 
```
(articleRoot \ "ArticleHeader" \ "AuthorGroup" \ "Author") map { authorNode =>
      val givenName = (authorNode \ "AuthorName" \ "GivenName").text
      val particle = (authorNode \ "AuthorName" \ "Particle").text
      val familyName = (authorNode \ "AuthorName" \ "FamilyName").text

      Author(givenName, particle, familyName)
    }
```

### Not supported
- Pattern matching has not been implemented i.e. no unapply specified 
- child() behaves slightly differently - specifically it does not return white space with label of #PCDATA. See VtdNodeSeqTest for details.
- mkString does not current return xml of the fragment
- The underlying library has some restrictions on node depth (256) and node name length (512) 
- VtdXml is not threadsafe. Recommended use is to create a pool and do all Xml expression inside using(...) e.g.:

```
 val pool = VtdXml.poolOf(20)
 ...
 pool.usingElem(bytes, elem => {
      val pubName = (elem \ "PublisherInfo" \ "PublisherName").text
      ...
      (pubName, ...)
 })      
```
 
