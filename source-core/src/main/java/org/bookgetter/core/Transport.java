package org.bookgetter.core;

import org.bookgetter.core.Models.FetchRequest;
import org.bookgetter.core.Models.FetchResponse;

@FunctionalInterface
public interface Transport { FetchResponse fetch(FetchRequest request) throws Exception; }
