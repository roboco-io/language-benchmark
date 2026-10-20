package realworld.controllers

import play.api.mvc.*
import realworld.http.*
import realworld.services.{ProfileService, Views}

final class ProfileController(cc: ControllerComponents, auth: Authenticator, profiles: ProfileService)
    extends ApiController(cc, auth) {

  def get(username: String): Action[?] = open { (_, a) =>
    profiles.get(username, a.map(_.user.id)).map(p => Ok(Views.profile(p)))
  }

  def follow(username: String): Action[?] = secured { (_, a) =>
    profiles.follow(username, a.user.id).map(p => Ok(Views.profile(p)))
  }

  def unfollow(username: String): Action[?] = secured { (_, a) =>
    profiles.unfollow(username, a.user.id).map(p => Ok(Views.profile(p)))
  }
}
