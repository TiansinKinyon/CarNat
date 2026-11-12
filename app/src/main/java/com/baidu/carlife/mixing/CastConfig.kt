package com.baidu.carlife.mixing

import android.os.Parcel
import android.os.Parcelable
import android.view.Surface

data class CastConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val surface: Surface?,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(width)
        dest.writeInt(height)
        dest.writeInt(dpi)
        dest.writeParcelable(surface, flags)
    }

    companion object CREATOR : Parcelable.Creator<CastConfig> {
        @Suppress("DEPRECATION")
        override fun createFromParcel(source: Parcel) = CastConfig(
            source.readInt(),
            source.readInt(),
            source.readInt(),
            source.readParcelable<Surface>(Surface::class.java.classLoader),
        )

        override fun newArray(size: Int) = arrayOfNulls<CastConfig>(size)
    }
}
