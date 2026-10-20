package io.realworld

import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

fun profileView(user: UserEntity, following: Boolean) = linkedMapOf(
    "username" to user.username,
    "bio" to user.bio,
    "image" to user.image,
    "following" to following,
)

@RestController
@RequestMapping("/api/profiles/{username}")
class ProfileController(
    private val users: UserRepository,
    private val follows: FollowRepository,
    private val auth: Auth,
) {
    private fun find(username: String) = users.findByUsername(username) ?: throw ApiException.notFound("profile")

    @GetMapping
    @Transactional(readOnly = true)
    fun get(@PathVariable username: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.optional(authorization)
        val target = find(username)
        val following = me != null && follows.existsByFollowerIdAndFolloweeId(me.id!!, target.id!!)
        return mapOf("profile" to profileView(target, following))
    }

    @PostMapping("/follow")
    @Transactional
    fun follow(@PathVariable username: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.required(authorization)
        val target = find(username)
        if (!follows.existsByFollowerIdAndFolloweeId(me.id!!, target.id!!)) {
            follows.save(FollowEntity(followerId = me.id!!, followeeId = target.id!!))
        }
        return mapOf("profile" to profileView(target, true))
    }

    @DeleteMapping("/follow")
    @Transactional
    fun unfollow(@PathVariable username: String, @RequestHeader("Authorization", required = false) authorization: String?): Any {
        val me = auth.required(authorization)
        val target = find(username)
        follows.deleteByFollowerIdAndFolloweeId(me.id!!, target.id!!)
        return mapOf("profile" to profileView(target, false))
    }
}
