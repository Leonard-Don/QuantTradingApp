package com.tianxian.quant.model

object IndustryPolicy {
    fun infer(code: String, name: String): String {
        val lowerName = name.lowercase()
        return when {
            code in setOf("600519", "000858", "600887") -> "消费"
            code in setOf("600036", "601318", "600030", "601166", "600000", "601398", "601288", "601939", "000001") -> "金融"
            code in setOf("300750", "002594", "601012", "600438") -> "新能源"
            code in setOf("300059", "002415", "000725", "002475", "688981") -> "科技"
            code in setOf("300760", "000538", "600276") -> "医药"
            lowerName.contains("银行") || lowerName.contains("证券") || lowerName.contains("保险") -> "金融"
            lowerName.contains("医") || lowerName.contains("药") -> "医药"
            lowerName.contains("电") || lowerName.contains("科技") || lowerName.contains("芯") -> "科技"
            else -> "综合"
        }
    }
}
