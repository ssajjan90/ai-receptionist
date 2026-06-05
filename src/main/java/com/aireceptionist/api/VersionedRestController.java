package com.aireceptionist.api;

import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping(produces = "application/vnd.aireceptionist.v1+json")
public abstract class VersionedRestController {
}
