package ru.netology.coroutines

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import ru.netology.coroutines.dto.Comment
import ru.netology.coroutines.dto.Post
import ru.netology.coroutines.dto.PostWithComments
import ru.netology.coroutines.dto.Author
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val gson = Gson()
private val BASE_URL = "http://127.0.0.1:9999"
private val client = OkHttpClient.Builder()
    .addInterceptor(HttpLoggingInterceptor(::println).apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

fun main() {
    with(CoroutineScope(EmptyCoroutineContext)) {
        launch {
            try {
                val posts = getPostsWithAuthors(client)
                    .map { post ->
                        async {
                            val comments = getCommentsWithAuthors(client, post.id)
                            PostWithComments(post, comments)
                        }
                    }.awaitAll()
                println(posts)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    Thread.sleep(30_000L)
}

suspend fun OkHttpClient.apiCall(url: String): Response {
    return suspendCoroutine { continuation ->
        Request.Builder()
            .url(url)
            .build()
            .let(::newCall)
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
    }
}

suspend fun <T> makeRequest(url: String, client: OkHttpClient, typeToken: TypeToken<T>): T? =
    withContext(Dispatchers.IO) {
        client.apiCall(url)
            .let { response ->
                if (response.code == 404) {
                    response.close()
                    return@withContext null
                }
                if (!response.isSuccessful) {
                    response.close()
                    throw RuntimeException(response.message)
                }
                val body = response.body ?: throw RuntimeException("response body is null")
                gson.fromJson(body.string(), typeToken.type)
            }
    }

suspend fun getPosts(client: OkHttpClient): List<Post> =
    makeRequest("$BASE_URL/api/slow/posts", client, object : TypeToken<List<Post>>() {}) ?: emptyList()

suspend fun getComments(client: OkHttpClient, postId: Long): List<Comment> =
    makeRequest("$BASE_URL/api/slow/posts/$postId/comments", client, object : TypeToken<List<Comment>>() {}) ?: emptyList()

suspend fun getAuthor(client: OkHttpClient, authorId: Long): Author? =
    makeRequest("$BASE_URL/api/authors/$authorId", client, object : TypeToken<Author>() {})

suspend fun getPostsWithAuthors(client: OkHttpClient): List<Post> {
    val posts = getPosts(client)
    return posts.map { post ->
        val author = getAuthor(client, post.authorId)
        post.copy(
            author = author?.name ?: "Unknown Author",
            authorAvatar = author?.avatar ?: "default_avatar_url"
        )
    }
}

suspend fun getCommentsWithAuthors(client: OkHttpClient, postId: Long): List<Comment> {
    val comments = getComments(client, postId)
    return comments.map { comment ->
        val author = getAuthor(client, comment.authorId)
        comment.copy(
            author = author?.name ?: "Unknown Author",
            authorAvatar = author?.avatar ?: "default_avatar_url"
        )
    }
}