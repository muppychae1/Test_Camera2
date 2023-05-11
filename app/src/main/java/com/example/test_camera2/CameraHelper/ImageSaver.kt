package com.example.test_camera2.CameraHelper

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

internal class ImageSaver(
    /**
     * The JPEG image
     */
    private val image: Image,

//    /**
//     * The file we save the image into.
//     */
//    private val file: File
    private val context: Context
) : Runnable {

    override fun run() {
//        val buffer = image.planes[0].buffer
//        val bytes = ByteArray(buffer.remaining())
//        buffer.get(bytes)
//        var output: FileOutputStream? = null
//        try {
//            output = FileOutputStream(file).apply {
//                write(bytes)
//            }
//        } catch (e: IOException) {
//            Log.e(TAG, e.toString())
//        } finally {
//            image.close()
//            output?.let {
//                try {
//                    it.close()
//                } catch (e: IOException) {
//                    Log.e(TAG, e.toString())
//                }
//            }
//        }
        val resolver = context.contentResolver
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera2_Test")
        }
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        val output = imageUri?.let { resolver.openOutputStream(it) }

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        try {
            output?.use {
                it.write(bytes)
            }
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        } finally {
            image.close()
            output?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                    Log.e(TAG, e.toString())
                }
            }
        }

    }

    companion object {
        /**
         * Tag for the [Log].
         */
        private val TAG = "ImageSaver"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
