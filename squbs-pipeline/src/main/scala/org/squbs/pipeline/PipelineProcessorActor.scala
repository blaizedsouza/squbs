package org.squbs.pipeline

import akka.actor._
import spray.can.Http.RegisterChunkHandler
import spray.http._

import scala.util.{Failure, Success}

/**
 * Created by jiamzhang on 2015/3/3.
 */
class PipelineProcessorActor(target: ActorRef, client: ActorRef, processor: Processor) extends Actor with ActorLogging with Stash {

  import context.dispatcher
  import processor._

  def onPostProcess: Receive = {
    case PostProcess(ctx) => postProcess(ctx)
    case other => client forward other // unknown message
  }

  override def receive = onRequest orElse onPostProcess

  def onRequest: Receive = {
    case ctx: RequestContext =>
      var newCtx = ctx
      try {
        newCtx = preInbound(ctx)
        if (newCtx.response != ResponseNotReady) {
	        finalOutput(newCtx)
        } else {
	        inbound(newCtx) onComplete {
		        case Success(result) =>
			        try {
				        val postResult = postInbound(result)
				        context.become(onResponse(postResult) orElse onPostProcess)
				        target ! postResult.payload
			        } catch {
				        case t: Throwable =>
					        log.error(t, "Error in postInbound processing")
					        self ! PostProcess(onRequestError(result, t))
			        }
		        case Failure(t) =>
			        log.error(t, "Error in inbound processing")
			        self ! PostProcess(onRequestError(newCtx, t))
	        }
        }
      } catch {
        case t: Throwable =>
          log.error(t, "Error in processing request")
          self ! PostProcess(onRequestError(newCtx, t))
      }
  }

  private def outboundForResponse(reqCtx: RequestContext) = {
    var newCtx = reqCtx
    try {
      newCtx = preOutbound(newCtx)
      outbound(newCtx) onComplete {
        case Success(result) =>
          self ! PostProcess(result)
        case Failure(t) =>
          log.error(t, "Error in processing response")
          self ! PostProcess(onResponseError(newCtx, t))
      }
    }
    catch {
      case t: Throwable =>
        log.error(t, "Error in processing response")
        self ! PostProcess(onResponseError(newCtx, t))
    }
  }

  private def outboundForChunkResponse(reqCtx: RequestContext) = {
    var newCtx = reqCtx
    try {
      newCtx = preOutbound(newCtx)
      outbound(newCtx) onComplete {
        case Success(result) =>
          self ! ReadyToChunk(result)
        case Failure(t) =>
          log.error(t, "Error in processing ChunkedResponseStart")
          self ! PostProcess(onResponseError(newCtx, t))
      }
    } catch {
      case t: Throwable =>
        log.error(t, "Error in processing ChunkedResponseStart")
        self ! PostProcess(onResponseError(newCtx, t))
    }
  }

  // ready to serve response from proxied actor/route
  def onResponse(reqCtx: RequestContext): Receive = {

    case resp: HttpResponse =>
      outboundForResponse(reqCtx.copy(response = NormalResponse(resp)))

    case ReadyToChunk(ctx) =>
      postProcess(ctx)
      unstashAll()
      context.become(onChunk(ctx) orElse onPostProcess)


    case respStart: ChunkedResponseStart =>
      outboundForChunkResponse(reqCtx.copy(response = NormalResponse(respStart)))

    case data@Confirmed(ChunkedResponseStart(resp), ack) =>
      outboundForChunkResponse(reqCtx.copy(response = NormalResponse(data, sender())))

    case chunk: MessageChunk => stash()

    case chunkEnd: ChunkedMessageEnd => stash()

    case Confirmed(data, ack) => stash()

    case rch@RegisterChunkHandler(handler) =>
      val chunkHandler = context.actorOf(Props(classOf[ChunkHandler], handler, self, processor, reqCtx))
      client ! RegisterChunkHandler(chunkHandler)

    case Status.Failure(t) =>
      log.error(t, "Receive Status.Failure")
      outboundForResponse(onResponseError(reqCtx, t))

    case t: Throwable =>
      log.error(t, "Receive Throwable")
      outboundForResponse(onResponseError(reqCtx, t))

  }


  def onChunk(reqCtx: RequestContext): Receive = {

    case chunk: MessageChunk =>
      finalOutput(reqCtx.copy(response = NormalResponse(processResponseChunk(reqCtx, chunk))))

    case chunkEnd: ChunkedMessageEnd =>
			finalOutput(reqCtx.copy(response = NormalResponse(processResponseChunkEnd(reqCtx, chunkEnd))))

    case data@Confirmed(mc@(_: MessageChunk), ack) =>
      val newChunk = processResponseChunk(reqCtx, mc)
			finalOutput(reqCtx.copy(response = NormalResponse(Confirmed(newChunk, ack), sender())))

    case AckInfo(rawAck, receiver) =>
      receiver tell(rawAck, self)
  }

  private def postProcess(ctx: RequestContext) = {
    finalOutput(postOutbound(ctx))
  }

  private def finalOutput(ctx: RequestContext) = {

    ctx.response match {
      case r: NormalResponse =>
        val response = r.responseMessage
        client ! response
        response match {
          case r@(_: HttpResponse | _: ChunkedMessageEnd) => context stop self
          case other =>
        }
      case r: ExceptionalResponse =>
        //TODO needs to check if chunk already start
        client ! r.response
        context stop self

      case other =>
        log.error("Unexpected response: " + other)
        client ! ExceptionalResponse.defaultErrorResponse
        context stop self
    }
  }
}

case class ReadyToChunk(ctx: RequestContext)

case class PostProcess(ctx: RequestContext)

private class ChunkHandler(realHandler: ActorRef, caller: ActorRef, processor: Processor, reqCtx: RequestContext) extends Actor {

  import processor._

  def receive: Actor.Receive = {
    case chunk: MessageChunk => realHandler tell(processRequestChunk(reqCtx, chunk), caller)

    case chunkEnd: ChunkedMessageEnd => realHandler tell(processRequestChunkEnd(reqCtx, chunkEnd), caller)

    case other => realHandler tell(other, caller)

  }
}