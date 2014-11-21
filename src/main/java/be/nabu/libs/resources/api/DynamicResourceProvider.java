package be.nabu.libs.resources.api;

import java.io.IOException;

import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;

public interface DynamicResourceProvider {
	public ReadableResource createDynamicResource(ReadableContainer<ByteBuffer> originalContent, String name, String contentType, boolean shouldClose) throws IOException;
}
