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
import java.io.RandomAccessFile;

public class CoffeeImageFile implements CoffeeImage {
	private final RandomAccessFile imageFile;
	private final CoffeeConfiguration conf;

	public CoffeeImageFile(String filename, CoffeeConfiguration conf) throws IOException {
		this.conf = conf;
		imageFile = new RandomAccessFile(filename, "rw");
		if (imageFile.length() == 0) {
			// Allocate a full file system image.
			imageFile.setLength(conf.startOffset + conf.fsSize);
		}
	}

	@Override
	public CoffeeConfiguration getConfiguration() {
		return conf;
	}

	@Override
	public void read(byte[] bytes, int size, int offset) throws IOException {
		imageFile.seek(conf.startOffset + offset);
		imageFile.read(bytes, 0, size);
	}

	@Override
	public void write(byte[] bytes, int size, int offset) throws IOException {
		imageFile.seek(conf.startOffset + offset);
		imageFile.write(bytes, 0, size);
	}

	@Override
	public void erase(int size, int offset) throws IOException {
		byte[] bytes = new byte[256];
		int chunkSize;

		while(size > 0) {
			chunkSize = Math.min(size, bytes.length);
			imageFile.seek(conf.startOffset + offset);
			imageFile.write(bytes, 0, chunkSize);
			size -= chunkSize;
			offset += chunkSize;
		}
	}

}
