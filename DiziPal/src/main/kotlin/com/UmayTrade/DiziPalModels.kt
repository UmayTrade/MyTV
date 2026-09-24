package com.UmayTrade

data class DizipalSearchData(
    var results: List<DizipalSearchItem>? = null
)

data class DizipalSearchItem(
    var title: String? = null,
    var url: String? = null,
    var poster: String? = null,
    var year: Int? = null,
    var type: String? = null
)
