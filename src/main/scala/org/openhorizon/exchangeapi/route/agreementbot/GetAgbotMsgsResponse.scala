package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.message.AgbotMsg

/** Response for GET /orgs/{orgid}/agbots/{id}/msgs */
final case class GetAgbotMsgsResponse(messages: List[AgbotMsg], lastIndex: Int)
