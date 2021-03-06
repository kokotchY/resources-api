package be.nabu.libs.resources;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import be.nabu.libs.resources.api.LocatableResource;
import be.nabu.libs.resources.api.ManageableContainer;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.api.ResourceFilter;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.utils.io.ContentTypeMap;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.Container;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;

public class ResourceUtils {

	public static ReadableResource wrapReadable(byte [] bytes, int offset, int length) {
		return new ReadableByteResource(bytes, offset, length);
	}
	
	public static List<Resource> find(ResourceContainer<?> container, ResourceFilter filter, boolean recursive) {
		List<Resource> result = new ArrayList<Resource>();
		for (Resource child : container) {
			if (filter.accept(child))
				result.add(child);
			if (recursive && child instanceof ResourceContainer)
				result.addAll(find((ResourceContainer<?>) child, filter, recursive));
		}
		return result;
	}
	
	public static Resource touch(URI uri, Principal principal) throws IOException {
		ManageableContainer<?> parent = (ManageableContainer<?>) mkdir(URIUtils.getParent(uri), principal);
		String name = URIUtils.getName(uri);
		Resource child = parent.getChild(name);
		return child == null ? parent.create(name, ContentTypeMap.getInstance().getContentTypeFor(name)) : child;
	}
	
	public static ResourceContainer<?> mkdir(URI uri, Principal principal) throws IOException {
		return mkdir(uri, principal, null);
	}
	
	public static ReadableContainer<ByteBuffer> toReadableContainer(URI uri, Principal principal) throws IOException {
		Resource resource = ResourceFactory.getInstance().resolve(uri, principal);
		if (resource == null)
			throw new FileNotFoundException("Could not find the resource " + uri);
		if (!(resource instanceof ReadableResource))
			throw new IOException("The resource at " + uri + " is not readable");
		return new ResourceReadableContainer((ReadableResource) resource);
	}
	
	public static WritableContainer<ByteBuffer> toWritableContainer(URI uri, Principal principal) throws IOException {
		Resource resource = ResourceFactory.getInstance().resolve(uri, principal);
		if (resource == null)
			resource = touch(uri, principal);
		if (resource == null)
			throw new FileNotFoundException("Could not find or create the resource " + uri);
		if (!(resource instanceof WritableResource))
			throw new IOException("The resource at " + uri + " is not writable");
		return new ResourceWritableContainer((WritableResource) resource);
	}
	
	public static Container<ByteBuffer> toContainer(URI uri, Principal principal) throws IOException {
		Resource resource = ResourceFactory.getInstance().resolve(uri, principal);
		if (resource == null)
			resource = touch(uri, principal);
		if (resource == null)
			throw new FileNotFoundException("Could not find or create the resource " + uri);
		if (!(resource instanceof ReadableResource))
			throw new IOException("The resource at " + uri + " is not readable");
		if (!(resource instanceof WritableResource))
			throw new IOException("The resource at " + uri + " is not writable");
		// this creates a composed container but the writable will not close the container
		// the composed container will close the writable first (to allow for flushing) so the readable should close the resource
		return IOUtils.wrap(
			new ResourceReadableContainer((ReadableResource) resource), 
			new ResourceWritableContainer(((WritableResource) resource), false)
		);
	}
	
	private static ResourceContainer<?> mkdir(URI uri, Principal principal, String pathToCreate) throws IOException {
		Resource resource = ResourceFactory.getInstance().resolve(uri, principal);
		if (resource != null) {
			if (!(resource instanceof ResourceContainer))
				throw new IOException("The resource " + uri + " already exists and is not a container");
			else {
				if (pathToCreate == null)
					return (ResourceContainer<?>) resource;
				else
					return mkdirs(resource, pathToCreate);
			}
		}
		else {
			URI parent = URIUtils.getParent(uri);
			pathToCreate = URIUtils.getName(uri) + (pathToCreate == null ? "" : "/" + pathToCreate);
			
			if (parent == null)
				throw new IOException("Could not find parent to " + uri + ", can not create child " + pathToCreate);
			
			return mkdir(parent, principal, pathToCreate);
		}
	}
	
	public static ResourceContainer<?> mkdirs(Resource resource, String path) throws IOException {
		// strip leading
		if (path.startsWith("/"))
			path = path.substring(1);
		// strip trailing
		if (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);
		return mkdirs((ResourceContainer<?>) resource, path.split("/"), 0);
	}
	
	private static ResourceContainer<?> mkdirs(ResourceContainer<?> resource, String [] path, int counter) throws IOException {
		if (path[counter].equals(".."))
			return (ResourceContainer<?>) mkdirs(resource.getParent(), path, counter + 1);
		else if (path[counter].equals("."))
			return mkdirs(resource, path, counter + 1);
		else {
			Resource child = resource.getChild(path[counter]);
			if (child == null) {
				if (!(resource instanceof ManageableContainer))
					throw new IOException("Can not manage " + getPath(resource) + ", failed to mkdirs()");
				child = ((ManageableContainer<?>) resource).create(path[counter], Resource.CONTENT_TYPE_DIRECTORY);
			}
			if (!(child instanceof ResourceContainer))
				throw new IOException("The child " + getPath(child) + " is not a resource container");
			if (counter == path.length - 1)
				return (ResourceContainer<?>) child;
			else
				return mkdirs((ResourceContainer<?>) child, path, counter + 1);
		}
	}
	
	public static Resource resolve(Resource resource, String path) throws IOException {
		// an empty string is the same as "."
		if (path.equals("") || path.equals("."))
			return resource;
		// the root itself
		else if (path.equals("/"))
			return getRoot(resource);
		// an absolute path to the root of the resource
		else if (path.startsWith("/")) {
			path = path.substring(1);
			resource = getRoot(resource);
		}
		if (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);
		return resolve(resource, path.split("/"), 0);
	}
	
	private static Resource resolve(Resource resource, String [] path, int counter) throws IOException {
		Resource target = null;
		// go up one
		if (path[counter].equals(".."))
			target = resource.getParent();
		else if (path[counter].equals("."))
			target = resource;
		else {
			if (!(resource instanceof ResourceContainer))
				throw new IOException("Only listable resources can have child resources, can not retrieve " + path[counter] + " from " + resource.getName() + ". Path: " + Arrays.asList(path));
			target = ((ResourceContainer<?>) resource).getChild(path[counter]);
		}
		if (target == null || counter == path.length - 1)
			return target;
		else
			return resolve(target, path, counter + 1);
	}
	
	public static Resource getRoot(Resource resource) {
		if (resource.getParent() != null) {
			return getRoot(resource.getParent());
		}
		else {
			return resource;
		}
	}
	
	public static String getPath(Resource resource) {
		// there is no parent
		if (resource.getParent() == null) {
			if (resource.getName() == null || resource.getName().equals(""))
				return "/";
			else
				return "/" + resource.getName();
		}
		else {
			String parentPath = ResourceUtils.getPath(resource.getParent());
			if (parentPath.endsWith("/"))
				parentPath = parentPath.substring(0, parentPath.length() - 1);
			return parentPath + "/" + resource.getName();
		}
	}
	
	public static URI getURI(Resource resource) {
		if (resource instanceof LocatableResource)
			return ((LocatableResource) resource).getURI();
		else if (resource != null && resource.getParent() != null) {
			URI parent = getURI(resource.getParent());
			if (parent != null) {
				try {
					String parentPath = parent.toString();
					if (parentPath.endsWith("/"))
						parentPath = parentPath.substring(0, parentPath.length() - 1);
					return new URI(parentPath + "/" + resource.getName());
				}
				catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return null;
	}
	
	public static Resource rename(Resource original, String name) throws IOException {
		ManageableContainer<?> parent = (ManageableContainer<?>) original.getParent();
		Resource renamed = copy(original, parent, name);
		parent.delete(original.getName());
		return renamed;
	}
	
	public static Resource copy(Resource original, ManageableContainer<?> target) throws IOException {
		return copy(original, target, original.getName());
	}
	
	public static Resource copy(Resource original, ManageableContainer<?> target, String newName) throws IOException {
		return copy(original, target, newName, false, false);
	}
	
	public static Resource copy(Resource original, ManageableContainer<?> target, String newName, boolean contentsOnly, boolean overwrite) throws IOException {
		Resource child = target.getChild(newName);
		if (!overwrite && child != null) {
			throw new IOException("The target '" + newName + "' already exists");
		}
		if (original instanceof ResourceContainer) {
			if (child == null) {
				child = contentsOnly ? target : target.create(newName, Resource.CONTENT_TYPE_DIRECTORY);
			}
			ManageableContainer<?> targetContainer = (ManageableContainer<?>) child;
			for (Resource childOriginal : (ResourceContainer<?>) original) {
				copy(childOriginal, targetContainer, childOriginal.getName(), false, overwrite);
			}
		}
		else if (original instanceof ReadableResource) {
			String contentType = original.getContentType();
			if (contentType == null) {
				contentType = URLConnection.guessContentTypeFromName(original.getName());
				if (contentType == null) {
					contentType = "application/octet-stream";
				}
			}
			if (child == null) {
				child = target.create(newName, contentType);
			}
			ReadableContainer<ByteBuffer> readable = ((ReadableResource) original).getReadable();
			try {
				WritableContainer<ByteBuffer> writable = new ResourceWritableContainer((WritableResource) child);
				try {
					IOUtils.copyBytes(readable, writable);
				}
				finally {
					writable.close();
				}
			}
			finally {
				readable.close();
			}
		}
		else {
			throw new IOException("Could not copy: " + original.getName());
		}
		return child;
	}
	
	public static void close(Resource resource) throws IOException {
		if (resource instanceof Closeable) {
			((Closeable) resource).close();
		}
		else if (resource.getParent() != null) {
			close(resource.getParent());
		}
	}
	
	public static void unzip(Resource zipResource, ResourceContainer<?> target) throws IOException {
		ReadableContainer<ByteBuffer> readable = ((ReadableResource) zipResource).getReadable();
		try {
			unzip(new ZipInputStream(new BufferedInputStream(IOUtils.toInputStream(readable))), target);
		}
		finally {
			readable.close();
		}
	}
	
	public static void unzip(ZipInputStream input, ResourceContainer<?> target) throws IOException {
		ZipEntry entry;
		while ((entry = input.getNextEntry()) != null) {
			String name = entry.getName();
			if (name.endsWith("/")) {
				continue;
			}
			if (name.startsWith("/")) {
				name = name.substring(1);
			}
			ResourceContainer<?> parent = name.contains("/") ? mkdirs(target, name.replaceAll("/[^/]+$", "")) : target;
			String contentType = URLConnection.guessContentTypeFromName(name);
			if (contentType == null) {
				contentType = "application/octet-stream";
			}
			Resource create = ((ManageableContainer<?>) parent).create(name.contains("/") ? name.replaceAll(".*/([^/]+)$", "$1") : name , contentType);
			WritableContainer<ByteBuffer> writable = ((WritableResource) create).getWritable();
			try {
				IOUtils.copyBytes(IOUtils.wrap(input), writable);
			}
			finally {
				writable.close();
			}
		}
	}
}
