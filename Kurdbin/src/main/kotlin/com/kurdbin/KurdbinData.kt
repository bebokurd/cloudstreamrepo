package com.kurdbin

import com.fasterxml.jackson.annotation.JsonProperty

class KurdbinList(
    @JsonProperty("data") val data: List<KurdbinItem>? = null,
    @JsonProperty("meta") val meta: KurdbinMeta? = null
)

class KurdbinVideo(
    @JsonProperty("data") val data: KurdbinItem? = null
)

class KurdbinItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("attributes") val attributes: KurdbinAttributes? = null
)

class KurdbinAttributes(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("hls") val hls: String? = null,
    @JsonProperty("video_link") val videoLink: String? = null,
    @JsonProperty("length") val length: String? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("body") val body: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("thumbnail") val thumbnail: KurdbinThumbWrap? = null
)

class KurdbinThumbWrap(
    @JsonProperty("data") val data: KurdbinThumb? = null
)

class KurdbinThumb(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("attributes") val attributes: KurdbinThumbAttributes? = null
)

class KurdbinThumbAttributes(
    @JsonProperty("url") val url: String? = null
)

class KurdbinMeta(
    @JsonProperty("pagination") val pagination: KurdbinPagination? = null
)

class KurdbinPagination(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("pageSize") val pageSize: Int? = null,
    @JsonProperty("pageCount") val pageCount: Int? = null,
    @JsonProperty("total") val total: Int? = null
)