/*
 * Copyright 2020 AstroLab Software
 * Author: Yash Datta
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.astrolabsoftware.grafink

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CLParserSpec extends AnyFunSuite with Matchers {

  val clParser = new CLParser {}

  test("CLParser correctly parses supplied configFile param") {
    val cFile = getClass.getResource("/application.conf").getPath
    val args  = Array("--config", cFile)

    val parser = clParser.parseOptions
    val result = parser.parse(args, ArgsConfig("defaultPath"))

    result should equal(Some(ArgsConfig(confFile = cFile)))
  }
}