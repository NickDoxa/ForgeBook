package com.forgebook.ai;

/**
 * Tag used by DispatchContext + Authorizer + RequestAuditLogger to distinguish
 * the three player-facing surfaces that invoke the AI pipeline.
 *
 *   CHAT_UI — Phase 4 in-inventory chat (ChatRequestPacket handler)
 *   ASK     — /forgebook ask <message...>  (Phase 3)
 *   ITEM    — /forgebook item [modid:item] (Phase 3, RAG single-shot)
 *
 * Used exclusively inside the server process; never serialized.
 */
public enum RequestKind { CHAT_UI, ASK, ITEM }
