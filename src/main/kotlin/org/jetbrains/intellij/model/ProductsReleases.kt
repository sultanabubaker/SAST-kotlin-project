package org.jetbrains.intellij.model

import java.io.Serializable
import javax.xml.bind.annotation.XmlAttribute
import javax.xml.bind.annotation.XmlElement
import javax.xml.bind.annotation.XmlRootElement

@XmlRootElement(name = "products")
data class ProductsReleases(

    @set:XmlElement(name = "product")
    var products: List<Product> = mutableListOf(),
) : Serializable

data class Product(

    @set:XmlAttribute
    var name: String = "",

    @set:XmlElement(name = "code")
    var codes: List<String> = mutableListOf(),

    @set:XmlElement(name = "channel")
    var channels: List<Channel> = mutableListOf(),
) : Serializable

data class Channel(

    @set:XmlAttribute
    var id: String = "",

    @set:XmlAttribute
    var name: String = "",

    @set:XmlAttribute
    var status: String = "",

    @set:XmlAttribute
    var url: String = "",

    @set:XmlAttribute
    var feedback: String = "",

    @set:XmlAttribute
    var majorVersion: Int = 0,

    @set:XmlAttribute
    var licensing: String = "",

    @set:XmlElement(name = "build")
    var builds: List<Build> = mutableListOf(),
) : Serializable

data class Build(

    @set:XmlAttribute
    var number: String = "",

    @set:XmlAttribute
    var version: String = "",

    @set:XmlAttribute
    var releaseDate: String = "",

    @set:XmlAttribute
    var fullNumber: String = "",

    @set:XmlElement
    var message: String = "",

    @set:XmlElement
    var blogPost: BlogPost? = null,

    @set:XmlElement(name = "button")
    var buttons: List<Button> = mutableListOf(),

    @set:XmlElement(name = "patch")
    var patches: List<Patch> = mutableListOf(),
) : Serializable

data class Button(

    @set:XmlAttribute
    var name: String = "",

    @set:XmlAttribute
    var url: String = "",

    @set:XmlAttribute
    var download: Boolean = false,
) : Serializable

data class Patch(

    @set:XmlAttribute
    var from: String = "",

    @set:XmlAttribute
    var size: String = "",

    @set:XmlAttribute
    var fullFrom: String = "",
) : Serializable

data class BlogPost(

    @set:XmlAttribute
    var url: String = "",
) : Serializable
