package org.jetbrains.intellij.model

import javax.xml.bind.annotation.XmlElement
import javax.xml.bind.annotation.XmlElementWrapper
import javax.xml.bind.annotation.XmlRootElement

@XmlRootElement(name = "metadata")
data class MavenMetadata(

    @set:XmlElement
    var groupId: String? = null,

    @set:XmlElement
    var artifactId: String? = null,

    @set:XmlElement
    var versioning: MavenMetadataVersioning? = null,
)

data class MavenMetadataVersioning(

    @set:XmlElement
    var latest: String? = null,

    @set:XmlElement
    var release: String? = null,

    @set:XmlElement
    var lastUpdated: String? = null,

    @set:XmlElement(name = "version")
    @set:XmlElementWrapper
    var versions: List<String>? = mutableListOf(),
)
