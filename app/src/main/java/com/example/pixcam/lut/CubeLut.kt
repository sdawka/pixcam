package com.example.pixcam.lut

/**
 * A parsed 3D LUT.
 *
 * [data] holds size^3 RGB triples in .cube order: red index varies fastest,
 * then green, then blue — data[3 * (r + size*g + size*size*b) + channel].
 * Values are linear in [0,1] after DOMAIN_MIN/MAX normalization, which is
 * exactly the layout glTexImage3D wants for a GL_RGB float texture of
 * width=height=depth=size (r → x, g → y, b → z).
 */
class CubeLut(
    val name: String,
    val size: Int,
    val data: FloatArray,
) {
    init {
        require(size in 2..129) { "unsupported LUT size $size" }
        require(data.size == size * size * size * 3) {
            "LUT data length ${data.size} does not match size $size"
        }
    }
}
