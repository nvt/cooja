/*
 * Copyright (c) 2009, Swedish Institute of Computer Science
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * Author: Nicolas Tsiftes
 *
 */

package org.contikios.coffee;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import org.contikios.coffee.CoffeeFS.CoffeeException;
import org.contikios.coffee.CoffeeFS.CoffeeFileException;

public class CoffeeManager {
	public enum Command { INSERT, EXTRACT, REMOVE, LIST, STATS }

	public static void main(String[] args) {
		String platform = "sky";
		Command command = Command.STATS;
		String filename = "";
		String fsImage;
		String usage = "Usage: java -jar coffee.jar " +
		        "[-p <hardware platform>] " +
		        "[-i|e|r <file>] " +
		        "[-l|s] " +
		        "<file system image>";

		if (args.length < 2) {
			System.err.println(usage);
			System.exit(1);
		}

		Pattern optionArg = Pattern.compile("-([pier])");

		for(int i = 0; i < args.length - 1; i++) {
			if (optionArg.matcher(args[i]).matches()) {
				if(i >= args.length - 2) {
					System.err.println(usage);
					System.exit(1);
				}
			}

			switch (args[i]) {
				case "-p" -> {
					platform = args[i + 1];
					i++;
				}
				case "-i" -> {
					command = Command.INSERT;
					filename = args[i + 1];
					i++;
				}
				case "-r" -> {
					command = Command.REMOVE;
					filename = args[i + 1];
					i++;
				}
				case "-e" -> {
					command = Command.EXTRACT;
					filename = args[i + 1];
					i++;
				}
				case "-l" -> command = Command.LIST;
				case "-s" -> command = Command.STATS;
				default -> {
					System.err.println(usage);
					System.exit(1);
				}
			}
		}
		fsImage = args[args.length - 1];

		try {
			CoffeeConfiguration conf = new CoffeeConfiguration(platform + ".properties");
			CoffeeFS coffeeFS = new CoffeeFS(new CoffeeImageFile(fsImage, conf));
			switch (command) {
				case INSERT -> {
					if (coffeeFS.getFiles().get(filename) != null) {
						System.err.println("error: file \"" +
										filename + "\" already exists");
						break;
					}
					if (coffeeFS.insertFile(filename) != null) {
						System.out.println("Inserted the local file \"" +
										filename +
										"\" into the file system image");
					}
				}
				case EXTRACT -> {
					if (!coffeeFS.extractFile(filename)) {
						System.err.println("Inexistent file: " +
										filename);
						System.exit(1);
					}
					System.out.println("Saved the file \"" +
									filename + "\"");
				}
				case REMOVE -> {
					coffeeFS.removeFile(filename);
					System.out.println("Removed the file \"" +
									filename +
									"\" from the Coffee file system image");
				}
				case LIST -> printFiles(coffeeFS.getFiles());
				case STATS -> printStatistics(coffeeFS);
				default -> {
					System.err.println("Unknown command!");
					System.exit(1);
				}
			}
		} catch (IOException | CoffeeFileException | CoffeeException e) {
			System.err.println(e.getMessage());
		}
	}

	public static void printStatistics(CoffeeFS coffeeFS) {
		int bytesWritten = 0;
		int bytesReserved = 0;
		int fileCount = 0;
		CoffeeConfiguration conf = coffeeFS.getConfiguration();

		try {
      for (var file : coffeeFS.getFiles().values()) {
        bytesWritten += file.getLength();
        bytesReserved += file.getHeader().getReservedSize();
        fileCount++;
      }
			bytesReserved *= conf.pageSize;
			System.out.println("File system size: " +
				conf.fsSize / 1024 + "kb");
			System.out.println("Allocated files: " + fileCount);
			System.out.println("Reserved bytes: " + bytesReserved + " (" + 
				(100 * ((float) bytesReserved / conf.fsSize)) +
				"%)");
			System.out.println("Written bytes: " + bytesWritten +
				" (" +
				(100 * ((float) bytesWritten / conf.fsSize)) +
				"%)");
		} catch (IOException e) {
			System.err.println("failed to determine the file length");
		}
	}

	public static void printFiles(Map<String, CoffeeFile> files) {
		try {
      for (var file : files.values()) {
        System.out.println(file.getName() + " " + file.getLength());
      }
		} catch (IOException e) {
			System.err.println("failed to determine the file length");
		}
	}
}
