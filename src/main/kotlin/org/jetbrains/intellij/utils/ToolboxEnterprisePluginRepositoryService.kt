package org.jetbrains.intellij.utils

import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.jetbrains.intellij.pluginRepository.internal.api.PluginRepositoryService
import org.jetbrains.intellij.pluginRepository.model.PluginUpdateBean
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ToolboxEnterprisePluginRepositoryService : PluginRepositoryService {

    @Multipart
    @POST("/api/ij-plugins/upload")
    override fun upload(
        @Part("pluginId") pluginId: Int,
        @Part("channel") channel: RequestBody?,
        @Part("notes") notes: RequestBody?,
        @Part file: MultipartBody.Part
    ): Call<PluginUpdateBean>

    @Multipart
    @POST("/api/ij-plugins/upload")
    override fun uploadByXmlId(
        @Part("xmlId") pluginXmlId: RequestBody,
        @Part("channel") channel: RequestBody?,
        @Part("notes") notes: RequestBody?,
        @Part file: MultipartBody.Part
    ): Call<PluginUpdateBean>
}
