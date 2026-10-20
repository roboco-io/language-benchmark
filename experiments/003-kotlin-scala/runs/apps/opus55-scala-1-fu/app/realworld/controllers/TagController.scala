package realworld.controllers

import play.api.mvc.*
import realworld.http.*
import realworld.services.{ArticleService, Views}

final class TagController(cc: ControllerComponents, auth: Authenticator, articles: ArticleService)
    extends ApiController(cc, auth) {

  def list: Action[?] = Action.async(articles.tagNames.map(names => Ok(Views.tags(names))))
}
