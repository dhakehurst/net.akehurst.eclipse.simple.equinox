package net.akehurst.eclipse.simple.equinox;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.equinox.internal.p2.artifact.repository.Activator;
import org.eclipse.equinox.internal.p2.artifact.repository.Messages;
import org.eclipse.equinox.internal.p2.artifact.repository.simple.SimpleArtifactRepository;
import org.eclipse.equinox.internal.p2.artifact.repository.simple.SimpleArtifactRepositoryIO;
import org.eclipse.equinox.internal.p2.core.AgentLocation;
import org.eclipse.equinox.internal.p2.core.ProvisioningAgent;
import org.eclipse.equinox.internal.p2.core.helpers.Tracing;
import org.eclipse.equinox.internal.p2.repository.CacheManager;
import org.eclipse.equinox.internal.p2.repository.CacheManagerComponent;
import org.eclipse.equinox.internal.p2.repository.Transport;
import org.eclipse.equinox.p2.core.IAgentLocation;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.repository.IRepositoryManager;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.osgi.util.NLS;

public class Utils {
	
	public static final String PROTOCOL_FILE = "file"; //$NON-NLS-1$
	private static final String JAR_EXTENSION = ".jar"; //$NON-NLS-1$
	private static final String XML_EXTENSION = ".xml"; //$NON-NLS-1$

	/**
	 * Returns a file in the local file system that contains the contents of the
	 * metadata repository at the given location.
	 */
	public static File getLocalFile(IProvisioningAgent agent, URI location, IProgressMonitor monitor) throws IOException, ProvisionException {
		File localFile = null;
		URI jarLocation = SimpleArtifactRepository.getActualLocation(location, true);
		URI xmlLocation = SimpleArtifactRepository.getActualLocation(location, false);
		// If the repository is local, we can return the repository file directly
		if (PROTOCOL_FILE.equals(xmlLocation.getScheme())) {
			//look for a compressed local file
			localFile = URIUtil.toFile(jarLocation);
			if (localFile.exists())
				return localFile;
			//look for an uncompressed local file
			localFile = URIUtil.toFile(xmlLocation);
			if (localFile.exists())
				return localFile;
			String msg = NLS.bind(Messages.io_failedRead, location);
			throw new ProvisionException(new Status(IStatus.ERROR, Activator.ID, ProvisionException.REPOSITORY_NOT_FOUND, msg, null));
		}
		// file is not local, create a cache of the repository metadata
//		CacheManager cache = (CacheManager) getAgent().getService(CacheManager.SERVICE_NAME);
		CacheManager cache = getCachManager(agent);
		if (cache == null)
			throw new IllegalArgumentException("Cache manager service not available"); //$NON-NLS-1$
		localFile = cache.createCache(location, SimpleArtifactRepository.CONTENT_FILENAME, monitor);
		if (localFile == null) {
			// there is no remote file in either form - this should not really happen as
			// createCache should bail out with exception if something is wrong. This is an internal
			// error.
			throw new ProvisionException(new Status(IStatus.ERROR, Activator.ID, ProvisionException.REPOSITORY_NOT_FOUND, Messages.repoMan_internalError, null));
		}
		return localFile;
	}
	
	static CacheManager cm;
	public static CacheManager getCachManager(IProvisioningAgent agent) {
		if (null==cm) {
			CacheManagerComponent cc = new CacheManagerComponent();
			cm = (CacheManager)cc.createService(agent);
		}
		return cm;
	}

	public static IArtifactRepository load(IProvisioningAgent agent, URI location, int flags, IProgressMonitor monitor, boolean acquireLock) throws ProvisionException {
		long time = 0;
		final String debugMsg = "Restoring artifact repository "; //$NON-NLS-1$
		if (Tracing.DEBUG_METADATA_PARSING) {
			Tracing.debug(debugMsg + location);
			time = -System.currentTimeMillis();
		}
		SubMonitor sub = SubMonitor.convert(monitor, 400);
		try {
			File localFile = getLocalFile(agent, location, sub.newChild(300));
			InputStream inStream = new BufferedInputStream(new FileInputStream(localFile));
			JarInputStream jarStream = null;
			try {
				//if reading from a jar, obtain a stream on the entry with the actual contents
				if (localFile.getAbsolutePath().endsWith(JAR_EXTENSION)) {
					jarStream = new JarInputStream(inStream);
					JarEntry jarEntry = jarStream.getNextJarEntry();
					String entryName = SimpleArtifactRepository.CONTENT_FILENAME + XML_EXTENSION;
					while (jarEntry != null && (!entryName.equals(jarEntry.getName()))) {
						jarEntry = jarStream.getNextJarEntry();
					}
					//if there is a jar but the entry is missing or invalid, treat this as an invalid repository
					if (jarEntry == null)
						throw new IOException(NLS.bind(Messages.io_invalidLocation, location));
				}
				//parse the repository descriptor file
				sub.setWorkRemaining(100);
				InputStream descriptorStream = jarStream != null ? jarStream : inStream;
				SimplerArtifactRepositoryIO io = new SimplerArtifactRepositoryIO(agent);
				SimplerArtifactRepository result = (SimplerArtifactRepository) io.read(location, descriptorStream, sub.newChild(100), acquireLock);
				result.initializeAfterLoad(location);
				if (result != null && (flags & IRepositoryManager.REPOSITORY_HINT_MODIFIABLE) > 0 && !result.isModifiable())
					return null;
				if (Tracing.DEBUG_METADATA_PARSING) {
					time += System.currentTimeMillis();
					Tracing.debug(debugMsg + "time (ms): " + time); //$NON-NLS-1$ 
				}
				return result;
			} finally {
				safeClose(jarStream);
				safeClose(inStream);
			}
		} catch (FileNotFoundException e) {
			String msg = NLS.bind(Messages.io_failedRead, location);
			throw new ProvisionException(new Status(IStatus.ERROR, Activator.ID, ProvisionException.REPOSITORY_NOT_FOUND, msg, e));
		} catch (IOException e) {
			String msg = NLS.bind(Messages.io_failedRead, location);
			throw new ProvisionException(new Status(IStatus.ERROR, Activator.ID, ProvisionException.REPOSITORY_FAILED_READ, msg, e));
		} finally {
			if (monitor != null)
				monitor.done();
		}
	}
	
	static private void safeClose(InputStream stream) {
		if (stream == null)
			return;
		try {
			stream.close();
		} catch (IOException e) {
			//ignore
		}
	}
}
