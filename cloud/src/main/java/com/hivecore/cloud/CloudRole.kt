package com.hivecore.cloud

/** Spec 15.5 / 18.3: the three jobs a cloud model does for the orchestrator; each role has its own slug chain in `models.json`. */
enum class CloudRole { PLANNER, ESCALATE_TEXT, ESCALATE_MULTIMODAL }
