package com.baidu.carlife.mixing

import android.os.Parcel
import android.os.Parcelable

data class MusicInfo(
    val source: String?,
    val song: String?,
    val artist: String?,
    val duration: Long,
    val playlistSize: Int,
    val index: Int,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(source)
        dest.writeString(song)
        dest.writeString(artist)
        dest.writeLong(duration)
        dest.writeInt(playlistSize)
        dest.writeInt(index)
    }

    companion object CREATOR : Parcelable.Creator<MusicInfo> {
        override fun createFromParcel(source: Parcel) = MusicInfo(
            source.readString(),
            source.readString(),
            source.readString(),
            source.readLong(),
            source.readInt(),
            source.readInt(),
        )

        override fun newArray(size: Int) = arrayOfNulls<MusicInfo>(size)
    }
}
