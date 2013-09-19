namespace java com.microsoft.corfu.sa 

include "common.thrift"

service CorfuStandaloneServer {

    /**
     * @param ctnt: a buffer of fixed size to append 
     * @return: a structure with two elements:
     * 	err is of type ErrorCode, and has the following possible values
     *				ERR_FULL if the log is full; 
     *				ERR_BADPARAM if bad parameter was passed, such as a null ctnt buffer
     *
     *  off is a long; if append succeeds, it holds the appended position 
     *
     * @throws org.apache.thrift.TException
     */
	common.CorfuOffsetWrap append(1:common.LogPayload ctnt),

    /**
     * @param offset: log position to read
     * @return a structure with two elements:
     * 	err is of type ErrorCode, and has the following possible values
     * 				OK - succeed
     * 				ERR_UNWRITTEN - trying to read a position which was not written
     * 				ERR_TRIMMED - 	trying to read a position which has been reclaimed via a trim call
     * 				ERR_BADPARAM - some mal-formatted parameter was passed
     * 
     *  ctnt is a byte array; if read succeeds, it contains the contents of the log at the requested offset
     *
     * @throws org.apache.thrift.TException
     */
	common.CorfuPayloadWrap read(1:i64 offset),

	 /**
     * @return a position one higher than the last appended position
     * @throws org.apache.thrift.TException
     */
     i64 check(),
	
    /**
     * Evict all log positions up to the marked offset. 
     * 
     * @param mark: a position one higher than the last trimmed offset
     * @return true if trim was successful, 
     * 			false if trimming an illegal position, such as an already trimmed offset, 
     * 			or a position beyond the current head of the log
     * @throws org.apache.thrift.TException
     */
	bool trim (1:i64 mark),
}