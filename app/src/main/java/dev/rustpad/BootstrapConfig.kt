package dev.rustpad

/** مرحلة من مراحل Bootstrap (أرشيف tar.zst يُفك داخل $PREFIX). */
data class Stage(
    val id: String,
    val url: String,
    val sha256: String = "",      // اتركه فارغاً لتخطي التحقق
    val optional: Boolean = false,
)

object BootstrapConfig {
    // TODO: غيّر OWNER/REPO إلى مستودعك، وانشر الأرشيفات في Release باسم bootstrap-v1
    const val BASE_URL = "https://github.com/OWNER/REPO/releases/download/bootstrap-v1"

    val STAGES = listOf(
        Stage("base", "$BASE_URL/bootstrap-base-aarch64.tar.zst"),
        Stage("apk-tools", "$BASE_URL/bootstrap-apk-tools-aarch64.tar.zst"),
        Stage("rust-analyzer", "$BASE_URL/bootstrap-rust-analyzer-aarch64.tar.zst", optional = true),
    )
}
