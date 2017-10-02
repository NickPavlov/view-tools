package grails.plugin.viewtools

import grails.converters.JSON
import grails.core.GrailsApplication
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang.Validate
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.grails.config.NavigableMap

import javax.annotation.PostConstruct

/**
 * A place for file resource related functionality which may required an application context or logged-in user.
 * Things related to the attachments directory or the tempUploadsDirectory or any other directory we reference from Config.groovy
 * would go here, especially if they require GString-like parsing.
 * @author Ken Roberts 2010-11-09
 */
class AppResourceService {
	static transactional = false
	//injected beans
	GrailsApplication grailsApplication

	ResourceLoader resourceLoader

	@PostConstruct
    public void init() {
		resourceLoader = grailsApplication.mainContext
	}

	/**
	 * returns the merged configObject using RallyDefaultConfig as a base
	 */
	// def getResourcesConfig(){
	// 	return resourcesConfig
	// }
	NavigableMap getResourcesConfig()  { return grailsApplication.config.nine.resources }

	/**
	 * if location starts with a / then it realtive to the war (web-app).
	 * For example, '/WEB-INF/invoice/greenbar.ftl
	 *
	 * if it does not start with a / then its considered relative to the rootLocation
	 * For example, '2010-11/23452.pdf' would look for file://myroot/2010-11/23452.pdf if that what was set in
	 * nine.attachments.directory
	 */
	Resource getResource(String location){
		String urlToUse = location
		if((!location.startsWith('/')) && !(location.startsWith('file:'))) {
			urlToUse = "file:${rootLocation}${location}/"
		}
		log.debug "AppResourceService.getResource with $urlToUse"
		resourceLoader.getResource(urlToUse)
	}

	/**
	 * Creates (or move) file relative to the MonthDirectory.
	 * If the data param is a File then its the temp file, rename and move.
	 * If data is a byte[] or a String then write to the new file
	 *
	 * @param attachmentId the ID of the Attachment record this file is going into.
	 * @param name optional name of the file
	 * @param extension The file extension to use.
	 * @param kind The "kind" of file from AttachmentKind.name if desired
	 * @param data The contents of the file as a File(temp file), String, byte array or null.
	 *             If its null then returns null
	 * @return A map
	 *        location:(string of the location relative to rootLocation),
	 *        file: the File instace that we put in that directory
	 */

	Map createAttachmentFile(Long attachmentId, String name, String extension, data, String location = null) {
		if(!location) location = "attachments.location"
		if(!data) return null
		def prefix
		if(name){
			prefix = "${name}_"
		}else if(data instanceof File){
			//TODO we should have an option pass in a name so we can name it logically like we are doing with file
			prefix = "${data.name}_"
		}else{
			prefix = ""
		}
		String destFileName = extension ? "${prefix}${attachmentId}.${extension}" : "${prefix}${attachmentId}"

		//setup the monthly dir for attachments
		File monthDir = getMonthDirectory(location)
		File file = new File(monthDir, destFileName)
		if(data) {
			if(data instanceof File) FileUtils.moveFile(data, file)
			if(data instanceof byte[]) FileUtils.writeByteArrayToFile(file, data)
			if(data instanceof String) FileUtils.writeStringToFile(file, data)
		}
		return [location:getRelativePath("attachments.location", file), file:file]
	}

	/**
	 * Creates a temp file inside the tempDir and, optionally, populates it with data.
	 * The file is guaranteed to not have a colliding name.
	 * If the originalFileName has a dot in the name, then the final file short name will be
	 * <whateverIsBeforeTheLastDot><someRandomCharacters>.<whateverIsAfterTheLastDot>
	 * For example, "readme.txt" would turn out something like "readme12a35c23.txt"
	 *
	 * @param originalFileName the name of the file that was uploaded
	 * @param data is the file contents, and can be String, byte[], or null.
	 * @return a non-null File instance, which has a unique name within the tempDir, and
	 *         if data is non-null will exist and will contain the data specified.
	 */
	File createTempFile(String originalFileName, data) {
		def baseName = FilenameUtils.getBaseName(originalFileName)
		if(baseName.length() < 3) baseName  = baseName + "tmp"
		def extension = FilenameUtils.getExtension(originalFileName)
		def tempDir = getLocation('tempDir')
		File tmpFile = File.createTempFile(baseName, (extension?".${extension}":''), tempDir)
		if(data) {
			if(data instanceof String) {
				FileUtils.writeStringToFile(tmpFile, data)
			} else if(data instanceof byte[]) {
				FileUtils.writeByteArrayToFile(tmpFile, data)
			}
			else if(data instanceof ByteArrayOutputStream) {
				tmpFile.withOutputStream {
					data.writeTo(it)
				}
			}
		}
		return tmpFile
	}

	/** Deletes any files in the temp directory which are in the list. */
	def deleteTempUploadedFiles(String attachmentListJson){
		if(attachmentListJson){
			def fileDetailsList = JSON.parse(attachmentListJson)
			fileDetailsList.each {fileDetails->
				FileUtils.forceDelete(new File(getLocation('tempDir'), fileDetails.tempFilename))
			}
		}
	}

	def deleteTempUploadedFiles(List attachmentList){
		attachmentList.each { file ->
			FileUtils.forceDelete(new File(getLocation('tempDir'), file.tempFilename))
		}
	}

	/** Shorthand way to get the attachment file with nothing but the location column contents. */
	File getFile(String location) {
		File file = new File(getLocation('attachments.location'), location)
	}

	/**
	 * returns the path relative to the the attachments directory
	 */
	String getAttachmentsRelativePath(File file) {
		//String relative = new File(base).toURI().relativize(new File(path).toURI()).getPath();
		return getRelativePath('attachments.location', file)
	}

	Map getCurrentTenant() {
		Closure currentTenant = grailsApplication.config.nine.resources.currentTenant
		Validate.notNull(currentTenant)
		return currentTenant.call()
	}

    public String getTenantUniqueKey() {
        def client = currentTenant
        return "${client.num}-${client.id}"
    }

	void forceMkdir(String path){
		FileUtils.forceMkdir(new File(path))
	}

	def getConfigVal(String configProperty) {
   		configProperty.split(/\./).inject(resourcesConfig) { co, k ->
     		co."${k}"
   		}
	}


// ====================== LocationService stuff.
	/** Gets the rootLocation which is the base for all the app-related directories.
	 * This must be a Closure in the config, it must return a string which will act like a GString and which
	 * refers to a directory which exists.
	 * You can use code inside the Closure to substitute values there, and also you can use:
	 *    - ${tenantId} (Client.id)
	 *    - ${tenantSubDomain} (Client.num)
	 * @return a File object referencing the directory.
	 * @throws FileNotFoundException, IllegalArgumentException
	 */
	File getRootLocation() {
		String rootName = getResourcesConfig().rootLocation(mergeClientValues()) // rootLocation exists by default
		File rootLocation = new File(rootName)
		return verifyOrCreateLocation(rootLocation, 'rootLocation', false) // Ensure that it exists on the filesystem.
	}

	/** Get a configured directory location from a nine.resources key.
	 * @param key The name of the key within the 'nine.resources' construct.
	 * @param env The map of substitutions allowed
	 * @param create True if the directory should be created if missing.
	 * @return one of:
	 *     a File which references a directory.  The directory exists.
	 *     a List of File, each entry of which is a directory which exists.
	 */
	File getLocation(String key, Map args = [:], boolean create=true) {
		def value = getResourcesConfig().toFlatConfig().get(key) // closure, string or null
		if(!value) throw new IllegalArgumentException("Application resource key '${key}' is not defined or returns an empty value.")
		String fileName
		if(value instanceof Closure) {
			fileName = value(mergeClientValues(args)) // Anything fancy must be Closure.
		} else {
			fileName = value as String // No substitution at all, just the string.
		}
		return getProperFile(fileName, key, create)
	}

	/** Get a list of script locations as absolute files. */
	List getScripts(Map args = [:]) {
		String key = 'scripts.locations'
		def closure = getResourcesConfig().toFlatConfig().get(key)
		log.debug "getScripts:  closure is ${closure}"
		if(!closure) throw new IllegalArgumentException("Application resource key '${key}' is not defined or returns an empty value.")
		List files = []
		closure(mergeClientValues(args)).each { name -> files << getProperFile(name, key, true) }
		return files
	}

	File getSubDirectory(String key, String relativePath, boolean create=true, Map args = [:]) {
		File base = getLocation(key, args, create)
		File subDirectory = new File(base, relativePath)
		return verifyOrCreateLocation(subDirectory, key, create, false)
	}

	/** getProperFile builds a File from a name and ensures it exists, or throws an error.
	 * If the name is absolute then it builds based on just that name.
	 * If the name is relative then it is built relative to rootLocation.
	 * @return the canonical File which exists.
	 */
	File getProperFile(String fileName, String key, boolean create=true) {
		boolean wasAbsolute = false
		if(!fileName) throw new IllegalArgumentException("Application resource key '${key}' is not defined or returns an empty value.")
		File dir = new File(getRootLocation(), fileName)
		File justName = new File(fileName)
		if(justName.isAbsolute()) {
			dir = justName
			wasAbsolute = true // Shouldn't create absolute files as this may mean a config was not properly set up.  (copied?)
		}
		return verifyOrCreateLocation(dir.canonicalFile, key, create, wasAbsolute)
	}

	/** verifyOrCreateLocation checks for validity of the directory before it's returned.
	 * @param dir (File) The File object to be tested.
	 * @param key (String) The key name in RallyDefaultConfig.groovy for example 'autocash.importDir'
	 * @param create (boolean) true to create the directory if absent, false to throw an error.
	 * @param wasAbsolute (boolean) True if the file was defined in the configuration as an absolute file.
	 */
	File verifyOrCreateLocation(File dir, String key, boolean create, wasAbsolute = false) {
		if(!dir) throw new IllegalArgumentException("Application resource key ${key} is not defined or returns an empty value.")
		if(!dir.exists()) {
			if(!create) throw new FileNotFoundException("Application resource ${key} defines a missing directory at ${dir.canonicalPath} which does not exist and must be manually created.")
			if(wasAbsolute) throw new FileNotFoundException("Application resource ${key} defines a missing directory at ${dir.canonicalPath} but the location does not exist.  It must be manually created.")
			dir.mkdirs()
		}
		if(!dir.isDirectory()) throw new IOException("Application resource ${key} should be a directory but ${dir.canonicalPath} is not.")
		return dir.canonicalFile
	}

	/** If the service which wants this directory offers some additional values for substitution they can
	 * be passed in with an args map.  Any tentantId or tenantSubDomain which is passed in should
	 * override the default values.
	 *
	 * At the time this was written there were no services offering additional values, but tenantId and
	 * tenantSubDomain are used.
	 *
	 * The returned map is a shallow-cloned map.  This means that if the map passed in did not have tenantId
	 * or tenantSubDomain then it will not have those values after the call, but if some existing value is
	 * changed in the destination map then it will be changed in the original as well.
	 *
	 * @param args A Map of values to be passed to the configuration Closure, if it's a Closure.
	 */
	Map mergeClientValues(Map args = [:]) {
		def client = currentTenant
		def localEnv = [tenantId:client.id, tenantSubDomain:client.num] << args
	}

	/** Gets a month directory for a configuration key.
	 * @param configKey the dot notation path from the rousources base ex: "attachments.location"
	 * @param create If true, create the directory if it's missing.
	 * @return a File object which references the month directory, or null on error.
	 */
	File getMonthDirectory(String configKey, boolean create=true) {
		String datePart = new Date().format('yyyy-MM')
		File baseDir = getLocation(configKey)
		File monthDir = new File(baseDir, datePart)
		return verifyOrCreateLocation(monthDir, configKey, create, false)
	}

	/** gets the path relative to some configured directory.  This is a shallow wrapper around
	 * getRelativePath(File,File).
	 * @param child The file to get the relative path of.
	 * @param key The key name in RallyDefaultConfig.groovy, for example 'attachments.location'
	 * @return a String containing the location of child relative to the directory described by key
	 */
	String getRelativePath(String key='rootLocation', File child) {
		return getRelativePath(getLocation(key), child)
	}

	/** gets the relative path of the child with respect to the parent
	 * The child must be inside of the parent or the resulting string is null.
	 */
	String getRelativePath(File parent, File child) {
		return parent.canonicalFile.toURI().relativize(child.canonicalFile.toURI()).getPath()
	}

	/**
	 * returns the path relative to the the temporary resource directory
	 */
	String getRelativeTempPath(File file) {	return getRelativePath('tempDir', file) }

}