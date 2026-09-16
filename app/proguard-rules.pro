# osmdroid 不携带 consumer rules（官方仅提供 proguard_osm_min.txt 样例）。
# tile source 解析、ManifestUtil、InfoWindow 等存在反射/资源名路径，R8 全量裁剪会失效。
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
# 这两个包属于 osmdroid 的可选集成，不在 AAR 内，未使用也会产生缺失类告警。
-dontwarn org.metalev.multitouch.controller.**
-dontwarn microsoft.mappoint.**
