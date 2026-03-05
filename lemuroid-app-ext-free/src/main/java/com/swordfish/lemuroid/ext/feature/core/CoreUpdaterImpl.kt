/*
 * CoreManager.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.ext.feature.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
) : CoreUpdater {
    // This is the last tagged versions of cores.
    companion object {
        private const val CORES_VERSION = "1.17.0"
    }

    private val baseUri = Uri.parse("https://github.com/Swordfish90/LemuroidCores/")

    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

    override suspend fun downloadCores(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        coreIDs.asFlow()
            .onEach { retrieveAssets(it, sharedPreferences) }
            .onEach { retrieveFile(context, it) }
            .collect()
    }

    private suspend fun retrieveFile(
        context: Context,
        coreID: CoreID,
    ): File {
        Timber.d("Retrieving core: $coreID, libretroFileName: ${coreID.libretroFileName}")
        
        val bundledLibrary = findBundledLibrary(context, coreID)
        if (bundledLibrary != null) {
            Timber.d("Found bundled core: ${bundledLibrary.absolutePath}")
            verifyCoreFilePermissions(bundledLibrary)
            return bundledLibrary
        }
        
        val externalLibrary = findExternalCoresLibrary(context, coreID)
        if (externalLibrary != null) {
            Timber.d("Found external core: ${externalLibrary.absolutePath}")
            verifyCoreFilePermissions(externalLibrary)
            return externalLibrary
        }
        
        Timber.d("Downloading core from GitHub")
        val downloadedCore = downloadCoreFromGithub(coreID)
        verifyCoreFilePermissions(downloadedCore)
        return downloadedCore
    }

    private fun verifyCoreFilePermissions(coreFile: File) {
        Timber.d("Core file permissions: readable=${coreFile.canRead()}, writable=${coreFile.canWrite()}, executable=${coreFile.canExecute()}")
        if (!coreFile.canExecute()) {
            Timber.d("Setting executable permission for core file")
            coreFile.setExecutable(true)
            Timber.d("Core file permissions after setting executable: executable=${coreFile.canExecute()}")
        }
    }

    private suspend fun findExternalCoresLibrary(
        context: Context,
        coreID: CoreID,
    ): File? = withContext(Dispatchers.IO) {
        val sharedPreferences = SharedPreferencesHelper.getLegacySharedPreferences(context)
        val coresFolderUri = sharedPreferences.getString(context.getString(com.swordfish.lemuroid.lib.R.string.pref_key_cores_folder), null)
        Timber.d("External cores folder URI: $coresFolderUri")
        
        if (coresFolderUri != null) {
            try {
                val uri = Uri.parse(coresFolderUri)
                // 使用 DocumentFile API 来访问 SAF 返回的目录
                val coresFolder = DocumentFile.fromTreeUri(context, uri)
                Timber.d("DocumentFile created: $coresFolder, isDirectory: ${coresFolder?.isDirectory}")
                
                if (coresFolder?.isDirectory == true) {
                    // 列出目录中的所有文件，以便我们可以看到目录中的内容
                    val files = coresFolder.listFiles()
                    Timber.d("Files in directory: ${files?.size}")
                    files?.forEach { file ->
                        Timber.d("File: ${file.name}, isDirectory: ${file.isDirectory}")
                    }
                    
                    // 递归查找核心文件
                    val foundDocumentFile = findCoreDocumentFile(coresFolder, coreID.libretroFileName)
                    if (foundDocumentFile != null) {
                        Timber.d("Found core document file: ${foundDocumentFile.name}, uri: ${foundDocumentFile.uri}")
                        
                        // 尝试将 DocumentFile 转换为 File
                        val coreFile = documentFileToFile(context, foundDocumentFile)
                        if (coreFile != null) {
                            Timber.d("Converted to File: ${coreFile.absolutePath}, exists: ${coreFile.exists()}")
                            return@withContext coreFile
                        }
                    } else {
                        Timber.d("Core file not found in external directory, looking for: ${coreID.libretroFileName}")
                    }
                } else {
                    Timber.d("External cores folder is not a directory or null")
                }
            } catch (e: Exception) {
                Timber.e("Error accessing external cores folder: $e")
            }
        } else {
            Timber.d("No external cores folder set")
        }
        return@withContext null
    }

    private fun findCoreDocumentFile(directory: DocumentFile, fileName: String): DocumentFile? {
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                val found = findCoreDocumentFile(file, fileName)
                if (found != null) return found
            } else if (file.name == fileName) {
                return file
            }
        }
        return null
    }

    private fun documentFileToFile(context: Context, documentFile: DocumentFile): File? {
        try {
            // 尝试获取文件路径
            val uri = documentFile.uri
            val filePath = uri.path
            if (filePath != null) {
                // 处理 content:// URI 路径
                val path = filePath.replace("/document/", "/storage/emulated/0/")
                val file = File(path)
                if (file.exists()) {
                    return file
                }
            }
            
            // 如果直接路径转换失败，尝试复制文件到缓存目录
            val cacheFile = File(context.cacheDir, documentFile.name ?: "core")
            cacheFile.deleteOnExit()
            
            context.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                cacheFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return cacheFile
            }
        } catch (e: Exception) {
            Timber.e("Error converting DocumentFile to File: $e")
        }
        return null
    }

    private suspend fun retrieveAssets(
        coreID: CoreID,
        sharedPreferences: SharedPreferences,
    ) {
        CoreID.getAssetManager(coreID)
            .retrieveAssetsIfNeeded(api, directoriesManager, sharedPreferences)
    }

    private suspend fun downloadCoreFromGithub(coreID: CoreID): File {
        Timber.i("Downloading core $coreID from github")

        val mainCoresDirectory = directoriesManager.getCoresDirectory()
        val coresDirectory =
            File(mainCoresDirectory, CORES_VERSION).apply {
                mkdirs()
            }

        val libFileName = coreID.libretroFileName
        val destFile = File(coresDirectory, libFileName)

        if (destFile.exists()) {
            return destFile
        }

        runCatching {
            deleteOutdatedCores(mainCoresDirectory, CORES_VERSION)
        }

        val uri =
            baseUri.buildUpon()
                .appendEncodedPath("raw/$CORES_VERSION/lemuroid_core_${coreID.coreName}/src/main/jniLibs/")
                .appendPath(Build.SUPPORTED_ABIS.first())
                .appendPath(libFileName)
                .build()

        try {
            downloadFile(uri, destFile)
            return destFile
        } catch (e: Throwable) {
            destFile.safeDelete()
            throw e
        }
    }

    private suspend fun downloadFile(
        uri: Uri,
        destFile: File,
    ) {
        val response = api.downloadFile(uri.toString())

        if (!response.isSuccessful) {
            Timber.e("Download core response was unsuccessful")
            throw Exception(response.errorBody()?.string() ?: "Download error")
        }

        response.body()?.writeToFile(destFile)
    }

    private suspend fun findBundledLibrary(
        context: Context,
        coreID: CoreID,
    ): File? =
        withContext(Dispatchers.IO) {
            File(context.applicationInfo.nativeLibraryDir)
                .walkBottomUp()
                .firstOrNull { it.name == coreID.libretroFileName }
        }

    private fun deleteOutdatedCores(
        mainCoresDirectory: File,
        applicationVersion: String,
    ) {
        mainCoresDirectory.listFiles()
            ?.filter { it.name != applicationVersion }
            ?.forEach { it.deleteRecursively() }
    }
}
