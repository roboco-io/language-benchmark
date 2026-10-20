package realworld.controllers

import play.api.mvc.*
import realworld.auth.Jwt
import realworld.services.UserService

class ProfileController(cc: ControllerComponents, jwt: Jwt, users: UserService)
    extends ApiController(cc, jwt, users):

  def get(username: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        viewer  <- optionalUser(req)
        profile <- users.profile(username, viewer.map(_.id))
      yield Ok(Views.profile(profile))
    }
  }

  def follow(username: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        (me, _) <- requireUser(req)
        profile <- users.follow(me.id, username)
      yield Ok(Views.profile(profile))
    }
  }

  def unfollow(username: String): Action[AnyContent] = Action.async { req =>
    handle {
      for
        (me, _) <- requireUser(req)
        profile <- users.unfollow(me.id, username)
      yield Ok(Views.profile(profile))
    }
  }
