package ship.f.engine.shared.core

fun <K, V> MutableMap<K, MutableSet<V>>.smartAdd(key: K?, value: V?) = apply {
    if (key != null && value != null) {
        if (!this.containsKey(key)) {
            this[key] = mutableSetOf()
        }
        this[key]!!.add(value)
    }
}

fun <K, V> MutableMap<K, MutableList<V>>.safeAdd(key: K?, value: V?) = apply {
    if (key != null && value != null) {
        if (!this.containsKey(key)) {
            this[key] = mutableListOf()
        }
        this[key]!!.add(value)
    }
}

fun printLines(vararg lines: Any){
    for (line in lines){
        println(line)
    }
}
