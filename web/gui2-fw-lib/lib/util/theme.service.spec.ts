/*
 * Copyright 2018-present Open Networking Foundation
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
import { TestBed, inject } from '@angular/core/testing';

import { LogService } from '../log.service';
import { ConsoleLoggerService } from '../consolelogger.service';
import { ThemeService } from './theme.service';

/**
 * ONOS GUI -- Util -- Theme Service - Unit Tests
 */
describe('ThemeService', () => {
    let log: LogService;

    beforeEach(() => {
        log = new ConsoleLoggerService();

        TestBed.configureTestingModule({
            providers: [ThemeService,
                { provide: LogService, useValue: log },
            ]
        });
    });

    it('should be created', inject([ThemeService], (service: ThemeService) => {
        expect(service).toBeTruthy();
    }));
});
