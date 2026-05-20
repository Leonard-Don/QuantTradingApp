package io.github.leonarddon.quanttrading.model

data class StockSearchEntry(
    val code: String,
    val name: String,
    val alias: String,
    val industry: String
)

object StockSearchIndex {
    private val entries = listOf(
        StockSearchEntry("600519", "贵州茅台", "gzmt maotai mt", "消费"),
        StockSearchEntry("000858", "五粮液", "wly wuliangye", "消费"),
        StockSearchEntry("000568", "泸州老窖", "lzlj luzhoulaojiao", "消费"),
        StockSearchEntry("000596", "古井贡酒", "gjgj gujinggongjiu", "消费"),
        StockSearchEntry("600809", "山西汾酒", "sxfj fenjiu", "消费"),
        StockSearchEntry("600887", "伊利股份", "ylgf yili", "消费"),
        StockSearchEntry("603288", "海天味业", "htwy haitian", "消费"),
        StockSearchEntry("000895", "双汇发展", "shfz shuanghui", "消费"),
        StockSearchEntry("600690", "海尔智家", "hezj haier", "消费"),
        StockSearchEntry("000333", "美的集团", "mdjt midea", "消费"),
        StockSearchEntry("000651", "格力电器", "gldq gree", "消费"),
        StockSearchEntry("601888", "中国中免", "zgzm zhongmian", "消费"),
        StockSearchEntry("600036", "招商银行", "zsyh cmb", "金融"),
        StockSearchEntry("601318", "中国平安", "zgpa pingan", "金融"),
        StockSearchEntry("600030", "中信证券", "zxzq citic", "金融"),
        StockSearchEntry("601166", "兴业银行", "xyyh cib", "金融"),
        StockSearchEntry("600000", "浦发银行", "pfyh spdb", "金融"),
        StockSearchEntry("601398", "工商银行", "gsyh icbc", "金融"),
        StockSearchEntry("601288", "农业银行", "nyyh abc", "金融"),
        StockSearchEntry("601939", "建设银行", "jsyh ccb", "金融"),
        StockSearchEntry("000001", "平安银行", "payh pinganbank", "金融"),
        StockSearchEntry("601601", "中国太保", "zgtb taibao", "金融"),
        StockSearchEntry("601688", "华泰证券", "htzq huatai", "金融"),
        StockSearchEntry("601211", "国泰君安", "gtja guotai", "金融"),
        StockSearchEntry("300750", "宁德时代", "ndsd catl ningde", "新能源"),
        StockSearchEntry("002594", "比亚迪", "byd biyadi", "新能源"),
        StockSearchEntry("601012", "隆基绿能", "ljln longji", "新能源"),
        StockSearchEntry("600438", "通威股份", "twgf tongwei", "新能源"),
        StockSearchEntry("300274", "阳光电源", "ygdy sungrow", "新能源"),
        StockSearchEntry("002812", "恩捷股份", "ejgf enjie", "新能源"),
        StockSearchEntry("002709", "天赐材料", "tccl tianci", "新能源"),
        StockSearchEntry("300014", "亿纬锂能", "ywln eve", "新能源"),
        StockSearchEntry("002466", "天齐锂业", "tqly tianqi", "新能源"),
        StockSearchEntry("002460", "赣锋锂业", "gfly ganfeng", "新能源"),
        StockSearchEntry("300124", "汇川技术", "hcjs huichuan", "新能源"),
        StockSearchEntry("600111", "北方稀土", "bfxt beifang", "新能源"),
        StockSearchEntry("300059", "东方财富", "dfcf eastmoney", "科技"),
        StockSearchEntry("002415", "海康威视", "hkws hikvision", "科技"),
        StockSearchEntry("000725", "京东方A", "jdfa boe", "科技"),
        StockSearchEntry("002475", "立讯精密", "lxjm luxshare", "科技"),
        StockSearchEntry("688981", "中芯国际", "zxgj smic", "科技"),
        StockSearchEntry("300033", "同花顺", "ths tonghuashun", "科技"),
        StockSearchEntry("603501", "韦尔股份", "wegf willsemi", "科技"),
        StockSearchEntry("002230", "科大讯飞", "kdxf iflytek", "科技"),
        StockSearchEntry("000063", "中兴通讯", "zxtx zte", "科技"),
        StockSearchEntry("600703", "三安光电", "sagd sanan", "科技"),
        StockSearchEntry("600745", "闻泰科技", "wtkj wingtech", "科技"),
        StockSearchEntry("688111", "金山办公", "jsbg wps", "科技"),
        StockSearchEntry("300760", "迈瑞医疗", "mryl mindray", "医药"),
        StockSearchEntry("000538", "云南白药", "ynby yunnanbaiyao", "医药"),
        StockSearchEntry("600276", "恒瑞医药", "hryy hengrui", "医药"),
        StockSearchEntry("300015", "爱尔眼科", "aeyk aier", "医药"),
        StockSearchEntry("300347", "泰格医药", "tgyy tigermed", "医药"),
        StockSearchEntry("603259", "药明康德", "ymkd wuxiapptec", "医药"),
        StockSearchEntry("600196", "复星医药", "fxyy fosun", "医药"),
        StockSearchEntry("600436", "片仔癀", "pzh pianzaihuang", "医药"),
        StockSearchEntry("000661", "长春高新", "ccgx changchun", "医药"),
        StockSearchEntry("002001", "新和成", "xhc xinhecheng", "医药"),
        StockSearchEntry("601899", "紫金矿业", "zjky zijin", "周期"),
        StockSearchEntry("601857", "中国石油", "zgsy petrochina", "周期"),
        StockSearchEntry("600028", "中国石化", "zgsh sinopec", "周期"),
        StockSearchEntry("601088", "中国神华", "zgsh shenhua", "周期"),
        StockSearchEntry("600019", "宝钢股份", "bggf baosteel", "周期"),
        StockSearchEntry("600309", "万华化学", "whhx wanhua", "周期"),
        StockSearchEntry("000002", "万科A", "wka vanke", "地产"),
        StockSearchEntry("600048", "保利发展", "blfz poly", "地产"),
        StockSearchEntry("601668", "中国建筑", "zgjz cscec", "建筑"),
        StockSearchEntry("601669", "中国电建", "zgdj powerchina", "建筑"),
        StockSearchEntry("600031", "三一重工", "syzg sany", "工业"),
        StockSearchEntry("601766", "中国中车", "zgzc crrc", "工业"),
        StockSearchEntry("600050", "中国联通", "zglt unicom", "通信"),
        StockSearchEntry("601728", "中国电信", "zgdx telecom", "通信"),
        StockSearchEntry("600941", "中国移动", "zgyd mobile", "通信"),
        StockSearchEntry("600900", "长江电力", "cjdl hydropower", "公用"),
        StockSearchEntry("600905", "三峡能源", "sxny threegorges", "公用"),
        StockSearchEntry("601816", "京沪高铁", "jhgt railway", "交通")
    )

    fun defaultCodes(limit: Int = 48): List<String> {
        return entries.take(limit).map { it.code }
    }

    fun searchCodes(keyword: String, limit: Int = 20): List<String> {
        val normalized = normalize(keyword)
        if (normalized.isBlank()) return emptyList()
        return entries.asSequence()
            .filter { it.matches(normalized) }
            .sortedWith(compareBy<StockSearchEntry> { it.relevance(normalized) }.thenBy { it.code })
            .take(limit)
            .map { it.code }
            .toList()
    }

    fun matches(code: String, keyword: String): Boolean {
        val normalized = normalize(keyword)
        if (normalized.isBlank()) return true
        return code.contains(normalized) ||
            entries.firstOrNull { it.code == code }?.matches(normalized) == true
    }

    fun industryFor(code: String): String? {
        return entries.firstOrNull { it.code == code }?.industry
    }

    private fun StockSearchEntry.matches(normalized: String): Boolean {
        return code.contains(normalized) ||
            normalize(name).contains(normalized) ||
            normalize(alias).contains(normalized)
    }

    private fun StockSearchEntry.relevance(normalized: String): Int {
        val aliasTokens = alias.split(" ").map(::normalize)
        return when {
            code == normalized -> 0
            code.startsWith(normalized) -> 1
            normalize(name).startsWith(normalized) -> 2
            aliasTokens.any { it.startsWith(normalized) } -> 3
            normalize(name).contains(normalized) -> 4
            normalize(alias).contains(normalized) -> 5
            else -> 6
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace(" ", "")
    }
}
