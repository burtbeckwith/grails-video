package com.cantina.lab
/* Copyright 2006-2007 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* For more information please visit www.cantinaconsulting.com
* or email info@cantinaconsulting.com
*/

import org.codehaus.groovy.grails.commons.ConfigurationHolder;
import org.springframework.beans.factory.InitializingBean
import com.cantina.lab.Movie

/**
 * A service that manages video assets for Grails.
 *
 * @author Matt Chisholm
 * @author Adam Stachelek
 */

class VideoService implements InitializingBean {

    boolean transactional = false

    def mvals = ConfigurationHolder.config.video

    // Todo: research video capture from flash ie, red5

    void afterPropertiesSet() {

        // Setup local asset path
        buildLocalPath()
    }

    def buildLocalPath() {
        def f = new File(ConfigurationHolder.config.video.location)

        if (!f.exists()) {
            f.mkdir()
        }
    }

    def performConversion(File sourceVideo, File targetVideo, File thumb) {

        String convertedMovieFileExtension = mvals.ffmpeg.fileExtension;
        def success = false;

        if (convertedMovieFileExtension == "flv") {
            //create a unique id for the temp file
            def uniqueId = new UUID(System.currentTimeMillis(),
                    System.currentTimeMillis() * System.currentTimeMillis()).toString();


            def tmpfile = mvals.location + uniqueId + "." + convertedMovieFileExtension;

            File tmp = new File(tmpfile) //temp file for contents during conversion


            String convertCmd = "${mvals.ffmpeg.path} -i ${sourceVideo.absolutePath} ${mvals.ffmpeg.conversionArgs} ${tmp.absolutePath}"
            String metadataCmd = "${mvals.yamdi.path} -i ${tmp.absolutePath} -o ${targetVideo.absolutePath} -l"
            String thumbCmd = "${mvals.ffmpeg.path} -i ${targetVideo.absolutePath} ${mvals.ffmpeg.makethumb} ${thumb.absolutePath}"

            success = exec(convertCmd); //kick off the command to convert movie to flv

            if (success) success = exec(metadataCmd); //kick off the command to add the metadata

            if (success) success = exec(thumbCmd); //kick off the command to create the thumb

            tmp.delete()  //delete the tmp file
        } else if (convertedMovieFileExtension == "mp4") {


            String convertCmd = "${mvals.ffmpeg.path} -i ${sourceVideo.absolutePath} ${mvals.ffmpeg.conversionArgs} ${targetVideo.absolutePath}"
            String metadataCmd = "${mvals.qtfaststart.path} ${targetVideo.absolutePath} ${targetVideo.absolutePath}.1"
            String deleteCmd = "rm -rf ${targetVideo.absolutePath}"
            String renameCmd = "mv ${targetVideo.absolutePath}.1 ${targetVideo.absolutePath}"
            String thumbCmd = "${mvals.ffmpeg.path} -i ${targetVideo.absolutePath} ${mvals.ffmpeg.makethumb} ${thumb.absolutePath}"

            success = exec(convertCmd); //kick off the command to convert movie to flv

            if (success) success = exec(metadataCmd); //kick off the command to generate/manipulate the metadata

            if (success) success = exec(deleteCmd); //kick off the command to generate/manipulate the metadata
            if (success) success = exec(renameCmd); //kick off the command to generate/manipulate the metadata

            if (success) success = exec(thumbCmd); //kick off the command to create the thumb

            //tmp.delete()  //delete the tmp file
        }

        return success;
    }

    def putMovie(Movie movie) {

        movie.status = Movie.STATUS_NEW
        movie.save(flush: true)

    }

    def deleteMovie(Movie movie) {

        File master = new File(movie.pathMaster)
        if (master.exists()) master.delete()

        File thumb = new File(movie.pathThumb)
        if (thumb.exists()) thumb.delete()

        File flv = new File(movie.pathFlv)
        if (flv.exists()) flv.delete()

        movie.delete()

    }

    def convertVideo(Movie movie) {

        //set movie status to in-progress
        movie.status = Movie.STATUS_INPROGRESS
        movie.save(flush: true)

        //create file to master file contents
        File vid = new File(movie.pathMaster)

        //create unique file paths for assets created during conversion (flv and thumb)
        String convertedMovieFileExtension = mvals.ffmpeg.fileExtension;
        String convertedMovieThumbnailExtension = "jpg";
        def convertedMovieFilePath = mvals.location + movie.key + "." + convertedMovieFileExtension;
        def convertedMovieThumbnailFilePath = mvals.location + movie.key + "." + convertedMovieThumbnailExtension;

        File flv = new File(convertedMovieFilePath)
        File thumb = new File(convertedMovieThumbnailFilePath)

        //convert the file into FLV
        performConversion(vid, flv, thumb)

        //set converted params on movie and save

        movie.pathFlv = convertedMovieFilePath
        movie.pathThumb = convertedMovieThumbnailFilePath
        movie.size = flv.length()
        movie.contentType = mvals.ffmpeg.contentType;

        movie.playTime = 0;
        movie.createDate = new Date();
        movie.url = "/movie/display/" + movie.id

        extractVideoMetadata(movie, convertedMovieFilePath)

        if (flv.exists()) {
            movie.status = Movie.STATUS_CONVERTED

        } else {
            movie.status = Movie.STATUS_FAILED
        }
        movie.save(flush: true);

    }



    def convertNewVideo() {
        log.info("Querying for \"" + Movie.STATUS_NEW + "\" movies.");
        def results = Movie.findAllByStatus(Movie.STATUS_NEW)

        log.info("Found " + results.size() + " movie(s) to convert");
        //TODO: kick off coversions in parallel
        results.each {
            log.info("Converting movie with key " + it.key);
            convertVideo(it)
        }
    }


    def exec(String command) {

        try {
            log.info("Executing " + command)
            def out = new StringBuilder()
            def err = new StringBuilder()
            def proc = command.execute();

            def exitStatus = proc.waitForProcessOutput(out, err)
            if (out) log.info "out:\n$out"
            if (err) log.info "err:\n$err"

            log.info("Process exited with status " + exitStatus);

            return (exitStatus == null || exitStatus == 0);

        } catch (Exception e) {
            log.error("Error while executing command " + command, e);
            return false;
        }

    }

    def extractVideoMetadata(Movie movie, String file) {

        // String command = "${mvals.ffprobe.path} -pretty -i " + file + " 2>&1 | grep \"Duration\" | cut -d ' ' -f 4 | sed s/,//"
        String command = "${mvals.ffprobe.path} ${mvals.ffprobe.params}" + file

        try {
            log.info("Executing " + command)
            def out = new StringBuilder()
            def err = new StringBuilder()
            def proc = command.execute();

            def exitStatus = proc.waitForProcessOutput(out, err)
            if (out) log.info "out:\n$out"
            if (err) log.info "err:\n$err"

            log.info("Process exited with status " + exitStatus);

            if (exitStatus == null || exitStatus == 0) {


                String originalOutput = out.append(err).toString()

                def tokens = []
                originalOutput.splitEachLine(": ,\n") { line ->
                    List list = line.toString().tokenize(": ,")
                    list.each { item ->
                        tokens << item
                    }
                }

                int i;
                for (i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i).toString().contains("Duration")) {
                        break;
                    }
                }

                movie.playTime = tokens.get(i + 1).toString().toInteger() * 3600 + tokens.get(i + 2).toString().toInteger() * 60 + tokens.get(i + 3).toString().toFloat()
                return true
            }
            return false

        } catch (Exception e) {
            log.error("Error while executing command " + command, e);
            return false;
        }

    }

}