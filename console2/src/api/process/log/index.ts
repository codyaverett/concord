/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
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
 * =====
 */

import { ConcordId, managedFetch } from '../../common';

export interface LogRange {
    unit?: string;
    length?: number;
    low?: number;
    high?: number;
}

export interface LogChunk {
    data: string;
    range: LogRange;
}

const str = (s?: {}): string => (s === undefined ? '' : String(s));

const formatRangeHeader = (range: LogRange) => ({
    Range: `bytes=${str(range.low)}-${str(range.high)}`
});

const parseRange = (s: string): LogRange => {
    const regex = /^bytes (\d*)-(\d*)\/(\d*)$/;
    const m = regex.exec(s);
    if (!m) {
        throw Object({ error: true, message: `Invalid Content-Range header: ${s}` });
    }

    return {
        unit: 'bytes',
        length: parseInt(m[3], 10),
        low: parseInt(m[1], 10),
        high: parseInt(m[2], 10)
    };
};

const toChunk = (data: string, range: LogRange): LogChunk => {
    // we assume that the data is aligned by \n
    // this will work only with our current implementation of the API
    return {
        data,
        range
    };
};

export const getLog = async (instanceId: ConcordId, range: LogRange): Promise<LogChunk> => {
    const opts = {
        headers: formatRangeHeader(range)
    };

    const resp = await managedFetch(`/api/v1/process/${instanceId}/log`, opts);

    const headers = resp.headers.get('Content-Range');
    if (!headers) {
        throw new Object({ error: true, message: `Range header is missing: ${instanceId}` });
    }

    const data = await resp.text();
    return toChunk(data, parseRange(headers));
};
