package controllers.web

import java.nio.file.{ Files, Paths }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorRef
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.{ Json, JsObject }
import play.api.mvc._
import tuktu.api._
import tuktu.web.js.JSGeneration

object Application extends Controller {
    val byteArray = Files.readAllBytes(Paths.get("public", "images", "pixel.gif"))

    /**
     * Creates tracking cookies if web.set_cookies is true and they aren't set yet
     */
    def cookies(implicit request: Request[AnyContent]): List[Cookie] =
        if (Cache.getAs[Boolean]("web.set_cookies").getOrElse(true))
            List(
                request.cookies.get("t_s_id") match {
                    case None => Some(Cookie("t_s_id", java.util.UUID.randomUUID.toString))
                    case _    => None
                },
                request.cookies.get("t_u_id") match {
                    case None => Some(Cookie("t_u_id", java.util.UUID.randomUUID.toString, Some(Int.MaxValue)))
                    case _    => None
                }).flatten
        else Nil

    /**
     * Handles a polymorphic JS-request
     */
    def handleRequest(id: String, isGET: Boolean)(implicit request: Request[AnyContent]): Future[Result] =
        // Try to get actual actor from hostmap
        Cache.getAs[collection.mutable.Map[String, ActorRef]]("web.hostmap").flatMap { hostmap =>
            hostmap.get(id)
        } match {
            case None => Future { BadRequest("// The analytics script is not enabled.") }
            case Some(actorRef) =>
                implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

                // Forward request and handle result
                (actorRef ? RequestPacket(request, isGET)).map {
                    case dp: DataPacket =>
                        // Get all the JS elements and output them one after the other
                        val (jsResult, nextFlow, includes) = JSGeneration.PacketToJsBuilder(dp)
                        Ok(views.js.Tuktu(nextFlow, jsResult,
                            Cache.getOrElse[String]("web.url")("http://localhost:9000") + routes.Application.TuktuJsPost(id).url,
                            includes))
                            .withCookies(cookies: _*)
                    case error: ErrorPacket =>
                        BadRequest("// Internal error occured.")
                    case _ =>
                        // Return blank
                        Ok("").as("text/javascript")
                }
        }

    /**
     * Invoke a flow based on a GET parameter that serves as ID
     */
    def TuktuJsGet(id: String) = Action.async { implicit request =>
        handleRequest(id, true)
    }

    /**
     * Handles analytics by id
     */
    def TuktuJsPost(id: String) = Action.async { implicit request =>
        request.body.asJson.getOrElse(Json.obj()).asOpt[JsObject]
            .flatMap { obj => (obj \ "f").asOpt[String] }
            .map { fn => id + "/" + fn }
            .map { id => handleRequest(id, false) }
            .getOrElse(Future { BadRequest("// The analytics script is not enabled.") })
    }

    /**
     * Serves an image instead of some JS
     */
    def imgGet(id: String) = Action.async { implicit request =>
        Future {
            // Try to get actual actor from hostmap
            Cache.getAs[collection.mutable.Map[String, ActorRef]]("web.hostmap").flatMap { _.get(id) } match {
                case None => Ok(byteArray).as("image/gif")
                case Some(actorRef) =>
                    // Send the Actor a DataPacket
                    actorRef ! RequestPacket(request, true)

                    // Return result
                    Ok(byteArray).as("image/gif").withCookies(cookies: _*)
            }
        }
    }
}